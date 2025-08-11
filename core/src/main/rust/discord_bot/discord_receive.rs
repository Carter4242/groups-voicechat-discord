use discortp::{
    rtp::{RtpExtensionPacket, RtpPacket},
    Packet, PacketSize,
};
use serenity::all::{ChannelId};
use songbird::{Event, EventContext, EventHandler};
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
use std::collections::HashMap;

// Global SSRC -> username map
static SSRC_USERNAME_MAP: OnceLock<Mutex<HashMap<u32, String>>> = OnceLock::new();

/// Update the SSRC -> username map
pub fn update_ssrc_username(ssrc: u32, username: String) {
    let map = SSRC_USERNAME_MAP.get_or_init(|| Mutex::new(HashMap::new()));
    map.lock().unwrap().insert(ssrc, username);
}

/// Get the username for a given SSRC, if known
pub fn get_username_for_ssrc(ssrc: u32) -> Option<String> {
    let map = SSRC_USERNAME_MAP.get_or_init(|| Mutex::new(HashMap::new()));
    map.lock().unwrap().get(&ssrc).cloned()
}

// Maintains the SSRC order for the current frame. This is only for the lifetime of the handler.
static LAST_SSRC_ORDER: OnceLock<Mutex<VecDeque<u32>>> = OnceLock::new();

#[derive(Clone)]
pub struct VoiceHandler {
    pub vc_id: ChannelId,
    pub bot: std::sync::Arc<super::DiscordBot>,
}

#[serenity::async_trait]
impl EventHandler for VoiceHandler {
    #[tracing::instrument(skip(self, ctx), fields(self.vc_id = %self.vc_id))]
    async fn act(&self, ctx: &EventContext<'_>) -> Option<Event> {
        // Handle SpeakingStateUpdate events to update SSRC->username map
        if let EventContext::SpeakingStateUpdate(speaking) = ctx {
            if let Some(user_id) = speaking.user_id {
                // Only update if this SSRC is not already mapped
                let already_mapped = {
                    let map = SSRC_USERNAME_MAP.get_or_init(|| Mutex::new(HashMap::new()));
                    map.lock().unwrap().contains_key(&speaking.ssrc)
                };
                if !already_mapped {
                    if let Some(username) = self.bot.lookup_username(user_id.0) {
                        update_ssrc_username(speaking.ssrc, username);
                    }
                }
            }
        }
        if let EventContext::VoiceTick(tick) = ctx {
            let buffer = &self.bot.discord_to_mc_buffer;
            if buffer.received_audio_tx.is_full() {
                tracing::warn!("VoiceHandler receive buffer is full for vc_id={}", self.vc_id);
                return None;
            }
            // Maintain a stable SSRC order for this frame, appending new SSRCs to the end.
            let ssrc_order_mutex = LAST_SSRC_ORDER.get_or_init(|| Mutex::new(VecDeque::new()));
            let mut ssrc_order = ssrc_order_mutex.lock().unwrap();
            let mut new_order = VecDeque::new();
            let mut payload_map = std::collections::HashMap::new();
            // Collect all current SSRCs and their data
            for (&ssrc, data) in tick.speaking.iter() {
                let Some(packet) = data.packet.as_ref() else {
                    continue;
                };
                let Some(rtp) = RtpPacket::new(&packet.packet) else {
                    tracing::warn!("VoiceHandler: Unable to parse RTP packet for vc_id={}", self.vc_id);
                    continue;
                };
                let extension = rtp.get_extension() != 0;
                let payload = rtp.payload();
                let payload = &payload[packet.payload_offset..packet.payload_end_pad];
                let start = if extension {
                    match RtpExtensionPacket::new(payload).map(|pkt| pkt.packet_size()) {
                        Some(s) => s,
                        None => {
                            tracing::warn!("VoiceHandler: Unable to parse extension packet for vc_id={}", self.vc_id);
                            continue;
                        }
                    }
                } else {
                    0
                };
                let payload = payload[start..].to_vec();
                payload_map.insert(ssrc, payload);
            }

            // Build the new order: keep previous order for still-active SSRCs, append new ones at the end
            for &ssrc in ssrc_order.iter() {
                if payload_map.contains_key(&ssrc) {
                    new_order.push_back(ssrc);
                }
            }
            for &ssrc in payload_map.keys() {
                if !new_order.contains(&ssrc) {
                    new_order.push_back(ssrc);
                }
            }
            // Update the static order for next frame
            *ssrc_order = new_order.clone();

            let mut user_payloads = Vec::new();
            for &ssrc in new_order.iter() {
                if let Some(payload) = payload_map.get(&ssrc) {
                    let username = get_username_for_ssrc(ssrc).unwrap_or_else(|| "Unknown User".to_string());
                    user_payloads.push((username, payload.clone()));
                }
            }

            if !user_payloads.is_empty() {
                if buffer.received_audio_tx.send(user_payloads).is_err() {
                    tracing::error!("VoiceHandler: received_audio_tx dropped for vc_id={}; bot may have been stopped or freed", self.vc_id);
                }
            }
        }
        None
    }
}