use discortp::{
    rtp::{RtpExtensionPacket, RtpPacket},
    Packet, PacketSize,
};
use serenity::all::ChannelId;
use songbird::{Event, EventContext, EventHandler};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};
static LAST_PACKET_TIME: AtomicU64 = AtomicU64::new(0);

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
            let mut payloads = Vec::new();
            let mut speaking_entries: Vec<_> = tick.speaking.iter().collect();
            speaking_entries.sort_by_key(|(ssrc, _)| *ssrc);
            for (_, data) in speaking_entries {
                let Some(packet) = data.packet.as_ref() else {
                    tracing::warn!("VoiceHandler: Missing packet for vc_id={}", self.vc_id);
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
                payloads.push(payload);
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