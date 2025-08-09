use discortp::{
    rtp::{RtpExtensionPacket, RtpPacket},
    Packet, PacketSize,
};
use serenity::all::ChannelId;
use songbird::{Event, EventContext, EventHandler};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
use std::collections::VecDeque;
use std::sync::{Mutex, OnceLock};
static LAST_PACKET_TIME: AtomicU64 = AtomicU64::new(0);

// Maintains the SSRC order for the current frame. This is only for the lifetime of the handler.
static LAST_SSRC_ORDER: OnceLock<Mutex<VecDeque<u32>>> = OnceLock::new();

pub struct VoiceHandler {
    pub vc_id: ChannelId,
    pub bot: std::sync::Arc<super::DiscordBot>,
}

#[serenity::async_trait]
impl EventHandler for VoiceHandler {
    #[tracing::instrument(skip(self, ctx), fields(self.vc_id = %self.vc_id))]
    async fn act(&self, ctx: &EventContext<'_>) -> Option<Event> {
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

            // Build payloads in the correct order
            let mut payloads = Vec::new();
            for &ssrc in new_order.iter() {
                if let Some(payload) = payload_map.get(&ssrc) {
                    payloads.push(payload.clone());
                }
            }

            if !payloads.is_empty() {
                // Log ms since last packet (for the batch)
                let now = SystemTime::now().duration_since(UNIX_EPOCH).expect("Time went backwards").as_millis() as u64;
                let last = LAST_PACKET_TIME.swap(now, Ordering::SeqCst);
                if last != 0 {
                    let delta = now.saturating_sub(last);
                    tracing::info!("VoiceHandler: {} ms since last Discord audio packet for vc_id={}", delta, self.vc_id);
                }

                if buffer.received_audio_tx.send(payloads).is_err() {
                    tracing::error!("VoiceHandler: received_audio_tx dropped for vc_id={}; bot may have been stopped or freed", self.vc_id);
                }
            }
        }
        None
    }
}