use discortp::{
    rtp::{RtpExtensionPacket, RtpPacket},
    Packet, PacketSize,
};
use serenity::all::ChannelId;
use songbird::{Event, EventContext, EventHandler};
use tracing::{debug, trace, warn};

pub struct VoiceHandler {
    pub vc_id: ChannelId,
    pub received_audio_tx: flume::Sender<Vec<u8>>,
    pub bot: std::sync::Arc<super::DiscordBot>,
}

#[serenity::async_trait]
impl EventHandler for VoiceHandler {
    #[tracing::instrument(skip(self, ctx), fields(self.vc_id = %self.vc_id))]
    async fn act(&self, ctx: &EventContext<'_>) -> Option<Event> {
        if let EventContext::VoiceTick(tick) = ctx {
            if self.received_audio_tx.is_full() {
               tracing::warn!("VoiceHandler receive buffer is full for vc_id={}", self.vc_id);
                debug!("Receive buffer is full");
                return None;
            }
            let Some(data) = tick.speaking.values().next() else {
                trace!("No one speaking");
                return None;
            };
            let Some(data) = data.packet.as_ref() else {
               tracing::warn!("VoiceHandler: Missing packet for vc_id={}", self.vc_id);
                debug!("Missing packet");
                return None;
            };
            let Some(rtp) = RtpPacket::new(&data.packet) else {
               tracing::warn!("VoiceHandler: Unable to parse RTP packet for vc_id={}", self.vc_id);
                warn!("Unable to parse packet");
                return None;
            };
            let extension = rtp.get_extension() != 0;

            let payload = rtp.payload();
            let payload = &payload[data.payload_offset..data.payload_end_pad];
            let start = if extension {
                match RtpExtensionPacket::new(payload).map(|pkt| pkt.packet_size()) {
                    Some(s) => s,
                    None => {
                       tracing::warn!("VoiceHandler: Unable to parse extension packet for vc_id={}", self.vc_id);
                        warn!("Unable to parse extension packet");
                        return None;
                    }
                }
            } else {
                0
            };

            let payload = payload[start..].to_vec();

            // Only route audio if bot is in Started state
            if !self.bot.is_audio_active() {
               tracing::warn!("VoiceHandler: DiscordBot is not in Started state; audio bridging is disabled for vc_id={}", self.vc_id);
                warn!("DiscordBot is not in Started state; audio bridging is disabled");
                return None;
            }

            self.bot.decode_and_route_to_groups(&payload);

            if self.received_audio_tx.send(payload).is_err() {
               tracing::error!("VoiceHandler: received_audio_tx dropped for vc_id={}; bot may have been stopped or freed", self.vc_id);
                warn!("received_audio rx dropped; bot may have been stopped or freed");
            }
        }
        None
    }
}