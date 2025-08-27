use discortp::{
    rtp::{RtpExtensionPacket, RtpPacket},
    Packet, PacketSize,
};
use serenity::all::{ChannelId};
use songbird::{Event, EventContext, EventHandler};

use std::collections::{VecDeque, HashMap};
use std::sync::Mutex;


#[derive(Clone)]
pub struct VoiceHandler {
    pub vc_id: ChannelId,
    pub bot: std::sync::Arc<super::DiscordBot>,
    pub ssrc_username_map: std::sync::Arc<Mutex<HashMap<u32, String>>>,
    pub ssrc_user_id_map: std::sync::Arc<Mutex<HashMap<u32, u64>>>,
    pub last_ssrc_order: std::sync::Arc<Mutex<VecDeque<u32>>>,
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
                    let map = self.ssrc_username_map.lock().unwrap();
                    map.contains_key(&speaking.ssrc)
                };
                if !already_mapped {
                    if let Some(username) = self.bot.lookup_username(user_id.0) {
                        let mut username_map = self.ssrc_username_map.lock().unwrap();
                        let mut user_id_map = self.ssrc_user_id_map.lock().unwrap();
                        username_map.insert(speaking.ssrc, username);
                        user_id_map.insert(speaking.ssrc, user_id.0);
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
            let mut ssrc_order = self.last_ssrc_order.lock().unwrap();
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
                    let (username, user_id) = {
                        let username_map = self.ssrc_username_map.lock().unwrap();
                        let user_id_map = self.ssrc_user_id_map.lock().unwrap();
                        let username = username_map.get(&ssrc).cloned().unwrap_or_else(|| "Unknown User".to_string());
                        let user_id = user_id_map.get(&ssrc).copied().unwrap_or(0);
                        (username, user_id)
                    };
                    user_payloads.push((username, user_id, payload.clone()));
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