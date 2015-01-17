/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.elopteryx.paint.upload.impl;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

/**
 * An efficient and flexible MIME Base64 implementation.
 *
 * @author Jason T. Greene
 */
class Base64 {
    
    private static final byte[] ENCODING_TABLE;
    private static final byte[] DECODING_TABLE = new byte[80];

    static {
        try {
            ENCODING_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".getBytes("ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException();
        }

        for (int i = 0; i < ENCODING_TABLE.length; i++) {
            int v = (ENCODING_TABLE[i] & 0xFF) - 43;
            DECODING_TABLE[v] = (byte)(i + 1);  // zero = illegal
        }
    }

    /**
     * Creates a state driven base64 decoder.
     *
     * <p>The Decoder instance is not thread-safe, and must not be shared between threads without establishing a
     * happens-before relationship.</p>
     *
     * @return a new createDecoder instance
     */
    public static Decoder createDecoder() {
        return new Decoder();
    }

    /**
     * Controls the decoding process.
     */
    public static final class Decoder {
        private int state;
        private int last;
        private static final int SKIP = 0x0FD00;
        private static final int MARK = 0x0FE00;
        private static final int DONE = 0x0FF00;
        private static final int ERROR = 0xF0000;

        private static int nextByte(ByteBuffer buffer, int state, int last, boolean ignoreErrors) throws IOException {
            return nextByte(buffer.get() & 0xFF, state, last, ignoreErrors);
        }

        private static int nextByte(int c, int state, int last, boolean ignoreErrors) throws IOException {
            if (last == MARK) {
                if (c != '=') {
                    throw new IOException("Expected padding character");
                }
                return DONE;
            }
            if (c == '=') {
                if (state == 2) {
                    return MARK;
                } else if (state == 3) {
                    return DONE;
                } else {
                    throw new IOException("Unexpected padding character");
                }
            }
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                return SKIP;
            }
            if (c < 43 || c > 122) {
                if (ignoreErrors) {
                    return ERROR;
                }
                throw new IOException("Invalid base64 character encountered: " + c);
            }
            int b = (DECODING_TABLE[c - 43] & 0xFF) - 1;
            if (b < 0) {
                if (ignoreErrors) {
                    return ERROR;
                }
                throw new IOException("Invalid base64 character encountered: " + c);
            }
            return b;
        }

        /**
         * Decodes one Base64 byte buffer into another. This method will return and save state
         * if the target does not have the required capacity. Subsequent calls with a new target will
         * resume reading where it last left off (the source buffer's position). Similarly not all of the
         * source data need be available, this method can be repetitively called as data is made available.
         *
         * <p>The decoder will skip white space, but will error if it detects corruption.</p>
         *
         * @param source the byte buffer to read encoded data from
         * @param target the byte buffer to write decoded data to
         * @throws java.io.IOException if the encoded data is corrupted
         */
        public void decode(ByteBuffer source, ByteBuffer target) throws IOException {
            if (target == null)
                throw new IllegalStateException();

            int last = this.last;
            int state = this.state;

            int remaining = source.remaining();
            int targetRemaining = target.remaining();
            int b = 0;
            while (remaining-- > 0 && targetRemaining > 0) {
                b = nextByte(source, state, last, false);
                if (b == MARK) {
                    last = MARK;
                    if (--remaining <= 0) {
                        break;
                    }
                    b = nextByte(source, state, last, false);
                }
                if (b == DONE) {
                    last = state = 0;
                    break;
                }
                if (b == SKIP) {
                    continue;
                }
                //  ( 6 | 2) (4 | 4) (2 | 6)
                if (state == 0) {
                    last = b << 2;
                    state++;
                    if (remaining-- <= 0) {
                        break;
                    }
                    b = nextByte(source, state, last, false);
                    if ((b & 0xF000) != 0) {
                        source.position(source.position() - 1);
                        continue;
                    }
                }
                if (state == 1) {
                    target.put((byte)(last | (b >>> 4)));
                    last = (b & 0x0F) << 4;
                    state++;
                    if (remaining-- <= 0 || --targetRemaining <= 0) {
                        break;
                    }
                    b = nextByte(source, state, last, false);
                    if ((b & 0xF000) != 0) {
                        source.position(source.position() - 1);
                        continue;
                    }
                }
                if (state == 2) {
                    target.put((byte) (last | (b >>> 2)));
                    last = (b & 0x3) << 6;
                    state++;
                    if (remaining-- <= 0 || --targetRemaining <= 0) {
                        break;
                    }
                    b = nextByte(source, state, last, false);
                    if ((b & 0xF000) != 0) {
                        source.position(source.position() - 1);
                        continue;
                    }
                }
                if (state == 3) {
                    target.put((byte)(last | b));
                    last = state = 0;
                    targetRemaining--;
                }
            }

            if (remaining > 0) {
                drain(source, b, state, last);
            }

            this.last = last;
            this.state = state;
        }

        private static void drain(ByteBuffer source, int b, int state, int last) {
            while (b != DONE && source.remaining() > 0) {
                try {
                    b = nextByte(source, state, last, true);
                } catch (IOException e) {
                    b = 0;
                }

                if (b == MARK) {
                    last = MARK;
                    continue;
                }

                // Not WS/pad
                if ((b & 0xF000) == 0) {
                    source.position(source.position() - 1);
                    break;
                }
            }

            if (b == DONE) {
                // SKIP one line of trailing whitespace
                while (source.remaining() > 0) {
                    b = source.get();
                     if (b == '\n') {
                        break;
                    }  else if (b != ' ' && b != '\t' && b != '\r') {
                        source.position(source.position() - 1);
                        break;
                    }

                }
            }
        }
    }
}
