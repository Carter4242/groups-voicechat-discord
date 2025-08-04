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
                debug!("Receive buffer is full");
                return None;
            }
            let Some(data) = tick.speaking.values().next() else {
                trace!("No one speaking");
                return None;
            };
            let Some(data) = data.packet.as_ref() else {
                debug!("Missing packet");
                return None;
            };
            let Some(rtp) = RtpPacket::new(&data.packet) else {
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
                warn!("DiscordBot is not in Started state; audio bridging is disabled");
                return None;
            }
            self.bot.decode_and_route_to_groups(&payload);

            if self.received_audio_tx.send(payload).is_err() {
                warn!("received_audio rx dropped; bot may have been stopped or freed");
            }
        }
        None
    }
}
