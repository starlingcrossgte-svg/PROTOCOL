package com.protocol.app.firmware

/**
 * The command grammar a RAM kernel speaks once it is running. The app drives the
 * kernel after upload, so it must know which framing to use — and that is NOT
 * derivable from the binary, so the caller selects it. (A kernel of either grammar
 * still uploads through the same 0x34/0xB6 path; this only affects how it is talked
 * to afterward.)
 *
 *  - [BARE] : command byte then args; reply opcode = cmd | 0x80   (e.g. 03 … -> 83 …)
 *  - [BEEF] : every message wrapped  BE EF | len | cmd | args; reply opcode = cmd | 0x40.
 *             (the FastECU-family SH7058 CAN kernels)
 */
enum class KernelProtocol { BARE, BEEF }
