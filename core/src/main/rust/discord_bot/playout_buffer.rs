// Adapted from Songbird's internal jitter buffer implementation (playout_buffer.rs)
// Copyright: See Songbird's license

//ISC License (ISC)

//Copyright (c) 2020, Songbird Contributors

//Permission to use, copy, modify, and/or distribute this software for any purpose with or without fee is hereby granted, provided that the above copyright notice and this permission notice appear in all copies.

//THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.


use std::collections::VecDeque;

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct StoredPacket {
    pub opus: Vec<u8>,
    pub decrypted: bool,
    pub seq: u16, // Add sequence number
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum PlayoutMode {
    Fill,
    Drain,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub enum PacketLookup {
    Packet(StoredPacket),
    MissedPacket,
    Filling,
}

#[derive(Debug)]
pub struct PlayoutBuffer {
    buffer: VecDeque<Option<StoredPacket>>,
    playout_mode: PlayoutMode,
    next_seq: u16,
    // For simplicity, timestamp logic is omitted for now.
    consecutive_store_fails: usize,
    capacity: usize,
}

impl PlayoutBuffer {

    pub fn new(capacity: usize, next_seq: u16) -> Self {
        Self {
            buffer: VecDeque::with_capacity(capacity),
            playout_mode: PlayoutMode::Fill,
            next_seq,
            consecutive_store_fails: 0,
            capacity,
        }
    }

    /// Resets the buffer to start from a new sequence number and stores the first packet.
    fn reset_buffer(&mut self, pkt_seq: u16, packet: StoredPacket) {
        self.buffer.clear();
        self.next_seq = pkt_seq;
        self.playout_mode = PlayoutMode::Fill;
        self.buffer.push_back(Some(packet));
        self.consecutive_store_fails = 0;
    }

    /// Force the buffer into Drain mode (emit all remaining packets, then switch to Fill)
    pub fn force_drain(&mut self) {
        self.playout_mode = PlayoutMode::Drain;
    }

    pub fn store_packet(&mut self, packet: StoredPacket) {
        let pkt_seq = packet.seq;
        let seq_diff = pkt_seq.wrapping_sub(self.next_seq) as i32;

        // If the sequence number is much less than expected (e.g., after client restart), reset buffer
        if seq_diff < -100 {
            self.reset_buffer(pkt_seq, packet);
            return;
        }

        // Out-of-order detection: drop packets older than next_seq
        if seq_diff < 0 {
            // Too old, drop
            return;
        }

        let desired_index = seq_diff as usize;
        // Error threshold for too-far-ahead packets
        let err_threshold = 8;
        if desired_index > 16 {
            // Too far ahead, increment store fails
            self.consecutive_store_fails += 1;
            // If too many consecutive store fails, treat as desync and reset buffer
            if self.consecutive_store_fails >= err_threshold {
                self.reset_buffer(pkt_seq, packet);
            }
            return;
        }
        
        while self.buffer.len() <= desired_index {
            self.buffer.push_back(None);
        }
        self.buffer[desired_index] = Some(packet);
        self.consecutive_store_fails = 0;
        if self.buffer.len() >= self.capacity {
            self.playout_mode = PlayoutMode::Drain;
        }
    }

    pub fn fetch_packet(&mut self) -> PacketLookup {
        if self.playout_mode == PlayoutMode::Fill {
            return PacketLookup::Filling;
        }
        let out = match self.buffer.pop_front() {
            Some(Some(pkt)) => {
                self.next_seq = self.next_seq.wrapping_add(1);
                PacketLookup::Packet(pkt)
            },
            Some(None) => {
                self.next_seq = self.next_seq.wrapping_add(1);
                PacketLookup::MissedPacket
            },
            None => {
                PacketLookup::Filling
            },
        };
        if self.buffer.is_empty() {
            self.playout_mode = PlayoutMode::Fill;
        }
        out
    }
}
