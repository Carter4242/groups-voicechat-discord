//! Watchdog for corrupted voice receive sessions.
//!
//! Symptom (seen repeatedly in production): songbird's udp_rx task logs a
//! continuous stream of "Illegal RTP message received" / "RTCP decryption
//! failed" and the affected speakers become inaudible in Minecraft until the
//! bot leaves and rejoins the voice channel (which renegotiates the session).
//!
//! This module counts those udp_rx errors via a tracing layer and, when the
//! rate is high enough to indicate a broken session (not just a stray packet),
//! asks the Java side to restart each started bot's voice session.

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Once;
use std::time::{Duration, Instant};

use once_cell::sync::Lazy;
use tracing::{info, warn};

/// Total number of receive-path errors logged by songbird's udp_rx task.
pub static RECEIVE_ERROR_COUNT: AtomicU64 = AtomicU64::new(0);

/// Monotonic anchor for cheap cross-thread timestamps.
static START: Lazy<Instant> = Lazy::new(Instant::now);

/// Milliseconds since process start (monotonic).
pub fn now_ms() -> u64 {
    START.elapsed().as_millis() as u64
}

const CHECK_INTERVAL_SECS: u64 = 10;
/// One continuous opus stream is 50 packets/sec, but speech is bursty (voice
/// activity gating), so a broken session shows up as ~100-500 failures per
/// 10s while someone talks. Benign noise observed in production (occasional
/// RTCP decrypt hiccups) stays in the single digits per second for a few
/// seconds, well under this threshold.
const TRIGGER_THRESHOLD: u64 = 100;
/// Never auto-restart more often than this.
const COOLDOWN_SECS: u64 = 300;
/// A bot that received parseable audio within this window is considered healthy
/// and is never restarted, even while errors are flooding: the corruption must
/// be on some other bot's session. Matches the check interval, so "healthy"
/// means "received audio during the same window the errors occurred in".
const STALE_AUDIO_MS: u64 = CHECK_INTERVAL_SECS * 1000;

/// Tracing layer that counts receive-path errors from songbird's udp_rx task.
pub struct ReceiveErrorCountingLayer;

impl<S: tracing::Subscriber> tracing_subscriber::Layer<S> for ReceiveErrorCountingLayer {
    fn on_event(
        &self,
        event: &tracing::Event<'_>,
        _ctx: tracing_subscriber::layer::Context<'_, S>,
    ) {
        if event
            .metadata()
            .target()
            .starts_with("songbird::driver::tasks::udp_rx")
        {
            RECEIVE_ERROR_COUNT.fetch_add(1, Ordering::Relaxed);
        }
    }
}

static WATCHDOG_STARTED: Once = Once::new();

/// Starts the monitor task (idempotent). Called when a bot starts a voice session.
pub fn ensure_started() {
    WATCHDOG_STARTED.call_once(|| {
        crate::runtime::RUNTIME.spawn(async {
            // Logged udp_rx errors PLUS frames songbird's DAVE layer dropped
            // silently (session never ready / missing ratchet) - the latter is
            // the "user talks, zero audio, zero log output" failure mode.
            let total = || {
                RECEIVE_ERROR_COUNT
                    .load(Ordering::Relaxed)
                    .wrapping_add(songbird::driver::DAVE_SILENT_DROPS.load(Ordering::Relaxed))
            };
            let mut last_count = total();
            let mut last_trigger: Option<Instant> = None;
            loop {
                tokio::time::sleep(Duration::from_secs(CHECK_INTERVAL_SECS)).await;
                let current = total();
                let delta = current.saturating_sub(last_count);
                last_count = current;
                if delta < TRIGGER_THRESHOLD {
                    continue;
                }
                let in_cooldown = last_trigger
                    .map(|t| t.elapsed() < Duration::from_secs(COOLDOWN_SECS))
                    .unwrap_or(false);
                if in_cooldown {
                    warn!(
                        "Voice receive path still failing ({delta} bad packets in {CHECK_INTERVAL_SECS}s), but auto-restart is in cooldown"
                    );
                    continue;
                }
                warn!(
                    "Voice receive session appears corrupted ({delta} unparseable/undecryptable packets in {CHECK_INTERVAL_SECS}s); checking which bots stopped receiving"
                );
                last_trigger = Some(Instant::now());

                // Snapshot the registry first so no map guard is held across awaits
                let bots: Vec<_> = super::BOT_REGISTRY
                    .iter()
                    .filter_map(|entry| entry.value().upgrade())
                    .collect();
                for bot in bots {
                    let started = bot
                        .state
                        .try_read()
                        .map(|s| matches!(*s, super::State::Started { .. }))
                        .unwrap_or(false);
                    if !started {
                        continue;
                    }
                    // Attribution: a bot that received parseable audio during the
                    // error window is healthy - the corruption is elsewhere. Only
                    // bots that got nothing are candidates (the Java side further
                    // requires that users are actually present in their channel).
                    let stale_ms = bot.ms_since_last_audio();
                    if stale_ms < STALE_AUDIO_MS {
                        info!(
                            "Bot (channel {:?}) received audio {stale_ms}ms ago; considered healthy, not restarting",
                            *bot.channel_id.lock()
                        );
                        continue;
                    }
                    warn!(
                        "Bot (channel {:?}) has not received parseable audio for {stale_ms}ms during an error flood; requesting restart",
                        *bot.channel_id.lock()
                    );
                    let vm = std::sync::Arc::clone(&bot.java_vm);
                    let obj = bot.java_bot_obj.clone();
                    let _ = tokio::task::spawn_blocking(move || {
                        match vm.attach_current_thread() {
                            Ok(mut env) => {
                                if let Err(e) =
                                    env.call_method(obj.as_obj(), "onVoiceReceiveCorrupted", "()V", &[])
                                {
                                    let _ = env.exception_clear();
                                    warn!(?e, "Failed to call onVoiceReceiveCorrupted on Java side");
                                }
                            }
                            Err(e) => warn!(?e, "Failed to attach JVM thread for voice watchdog"),
                        }
                    })
                    .await;
                }
            }
        });
    });
}
