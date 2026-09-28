package com.example.tvremote;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Voice streaming copied from the tested Cast_Remote implementation.
 *
 * Flow:
 *  1) RemoteSession sends KEYCODE_SEARCH.
 *  2) Wait briefly for the TV voice UI.
 *  3) RemoteVoiceBegin with a locally generated session id.
 *  4) Stream raw 16-bit mono 8 kHz PCM.
 *  5) RemoteVoiceEnd when stopped / TV ends / safety timeout.
 */
final class VoiceRecorder implements Runnable {
    interface Sink {
        void toast(String m);
        void started();
        void finished();
    }

    private static final int MAX_MS = 8000;
    private static final int RATE = 8000;
    private static final int CHUNK_BYTES = 20 * 1024;

    private final RemoteSession session;
    private final Sink sink;
    private volatile boolean stop;

    VoiceRecorder(RemoteSession s, Sink sink) {
        this.session = s;
        this.sink = sink;
    }

    void requestStop() {
        stop = true;
    }

    private static AudioRecord make(int rate) {
        int[] sources = {
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        for (int source : sources) {
            AudioRecord r = tryMake(rate, source);
            if (r != null) return r;
        }
        return null;
    }

    private static AudioRecord tryMake(int rate, int source) {
        try {
            int min = AudioRecord.getMinBufferSize(
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (min <= 0) return null;

            AudioRecord r = new AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, rate * 2 * 2)
            );

            if (r.getState() == AudioRecord.STATE_INITIALIZED) return r;
            r.release();
        } catch (Throwable t) {
            TvLog.d("AudioRecord " + rate + "/" + source + ": " + t);
        }
        return null;
    }

    @Override
    public void run() {
        AudioRecord rec = null;
        long sessionId = RemoteSession.VOICE_FAILED;
        boolean everSent = false;

        try {
            int decim = 1;

            rec = make(RATE);
            if (rec == null) {
                rec = make(16000);
                decim = 2;
            }

            if (rec == null) {
                sink.toast("Mic start nahi ho paya");
                return;
            }

            // This is the tested Cast_Remote order:
            // Search first, short delay, then RemoteVoiceBegin.
            session.key(84, Msgs.DIR_SHORT);
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }

            sessionId = session.startVoice();
            if (sessionId == RemoteSession.VOICE_FAILED) {
                sink.toast("Voice session start nahi hua");
                return;
            }

            rec.startRecording();
            sink.started();

            long start = System.currentTimeMillis();
            byte[] raw = new byte[CHUNK_BYTES * decim];
            int filled = 0;

            while (!session.isClosed()) {
                int n = rec.read(raw, filled, raw.length - filled);

                if (n > 0) filled += n;

                boolean full = filled == raw.length;
                boolean flushRemainder =
                        (stop || n < 0) && filled > 0;

                if (full || flushRemainder) {
                    byte[] pcm;
                    int len;

                    if (decim == 1) {
                        pcm = raw;
                        len = filled;
                    } else {
                        pcm = half(raw, filled);
                        len = pcm.length;
                    }

                    session.sendVoiceChunk(pcm, len);
                    everSent = true;
                    filled = 0;
                }

                if (stop
                        || n < 0
                        || System.currentTimeMillis() - start > MAX_MS) {
                    break;
                }
            }
        } catch (Throwable t) {
            TvLog.d("voice: " + t);
            sink.toast("Voice error: " + t.getClass().getSimpleName());
        } finally {
            try {
                if (sessionId != RemoteSession.VOICE_FAILED) {
                    session.stopVoice();
                    if (!everSent) sink.toast("Mic se data nahi mila");
                }
            } catch (Throwable ignored) {
            }

            try {
                if (rec != null) {
                    rec.stop();
                    rec.release();
                }
            } catch (Throwable ignored) {
            }

            sink.finished();
        }
    }

    /** 16 kHz -> 8 kHz, averaging each pair of 16-bit little-endian samples. */
    private static byte[] half(byte[] in, int len) {
        int n = len / 4;
        byte[] out = new byte[n * 2];

        for (int i = 0; i < n; i++) {
            int a = (short) ((in[i * 4] & 0xFF)
                    | (in[i * 4 + 1] << 8));
            int b = (short) ((in[i * 4 + 2] & 0xFF)
                    | (in[i * 4 + 3] << 8));

            int v = (a + b) / 2;
            out[i * 2] = (byte) v;
            out[i * 2 + 1] = (byte) (v >> 8);
        }

        return out;
    }
}
