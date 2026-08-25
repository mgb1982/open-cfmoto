package dev.zanderp.opencfmoto

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 16-byte CmdBaseHead frame used on the PXC ctrl socket.
 *   [0..3]  uint32 cmdType   (little-endian)
 *   [4..7]  uint32 totalLen  (= 16 + payload.size)
 *   [8..11] uint32 magic     (= cmdType XOR totalLen)
 *   [12..15] reserved (zero)
 *   payload[totalLen - 16]
 *
 * Mirrors net.easyconn.carman.sdk_communication.CmdBaseHead in the decompiled SDK.
 */
data class PxcFrame(val cmd: Int, val payload: ByteArray) {

    fun cmdHex(): String = "0x" + cmd.toUInt().toString(16)

    fun write(out: OutputStream) {
        val totalLen = 16 + payload.size
        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0, cmd)
        header.putInt(4, totalLen)
        header.putInt(8, cmd xor totalLen)
        // bytes 12..15 left at zero
        synchronized(out) {
            out.write(header.array())
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }
    }

    companion object {
        // Channel-selection cmds. First frame on a fresh ctrl socket selects which
        // PXC channel this connection belongs to. Server echoes back channelId+1 on accept.
        // Decoded from net.easyconn.carman.sdk_communication.PXCService.handleConnect().
        const val CMD_CHANNEL_CAR_CTRL    = 65536      // 0x10000   ack=0x10001
        const val CMD_CHANNEL_CAR_DATA    = 131072     // 0x20000   ack=0x20001
        const val CMD_CHANNEL_RV_CTRL     = 196608     // 0x30000   ack=0x30001  Carbit "RV" (regular bike)
        const val CMD_CHANNEL_RV_DATA     = 262144     // 0x40000   ack=0x40001
        // MCULite is the lightweight variant used by CFMoto/Zeeho clusters (this bike).
        // SDK source uses `android.R.drawable.alert_dark_frame` etc. as opaque cmd id literals;
        // those resolve to ints from android.jar at compile/link time.
        @JvmField val CMD_CHANNEL_MCULITE_CTRL = android.R.drawable.alert_dark_frame
        @JvmField val CMD_CHANNEL_MCULITE_DATA = android.R.layout.activity_list_item
        @JvmField val CMD_CHANNEL_MCU_CTRL     = android.R.color.darker_gray
        @JvmField val CMD_CHANNEL_MCU_DATA     = android.R.array.emailAddressTypes

        // ECTinyPlus protobuf-payload cmds (within an MCULite ctrl session).
        @JvmField val CMD_MCU_CLIENT_INFO   = android.R.drawable.checkbox_on_background
        @JvmField val CMD_MCU_START_MIRROR  = android.R.drawable.ic_lock_idle_low_battery
        @JvmField val CMD_MCU_CHECK_OTA     = android.R.drawable.ic_lock_power_off
        @JvmField val CMD_MCU_START_OTA     = android.R.drawable.ic_menu_help

        // ---- Standard Car PXC, the protocol THIS bike (CFDL16-6GUV) actually uses ----
        // Verified live in cfmoto-tcp-v5.log. Phone = server; bike connects back & sends these.
        const val CMD_MDNS_RESPOND        = 0x70000010 // phone→bike probe on :10930 (JSON), ack 0x70000011
        const val CMD_MDNS_RESPOND_ACK    = 0x70000011 // bike→phone {"status":true|false}
        const val CMD_HEARTBEAT           = 0x70000000 // ↔  ack = 0x70000001
        const val CMD_HEARTBEAT_ACK       = 0x70000001
        const val CMD_CLIENT_INFO_RLY     = 0x10011    // phone→bike reply to CLIENT_INFO (0x10010)
        const val CMD_QUERY_SPEED         = 0x10690    // bike→phone {usbSpeed,wifiSpeed}; reply 0x10691
        const val CMD_QUERY_SPEED_RLY     = 0x10691
        const val CMD_CHECK_SN            = 0x103e0    // bike→phone {client_set,sn}; reply 0x103e1 + result
        const val CMD_CHECK_SN_ACK        = 0x103e1
        const val CMD_CHECK_SN_RESULT     = 0x201c0    // phone→bike {isOk,...}; bike acks 0x201c1
        // BIKE B (CFDL26 / MotoPlay) log/report frame — bike→phone JSON {"log":...} after CAR_DATA
        // select. The older CFDL16 unit never sends this. Ack (0x10781) is experimental — the CFDL26
        // unit stalls (never opens the media ports) when we leave it unanswered. See Cfdl26Profile.
        const val CMD_LOG_REPORT          = 0x10780
        const val CMD_LOG_REPORT_ACK      = 0x10781
        // More CFDL26 / CRCP post-CHECK_SN notify frames (bike→phone JSON), each expecting a cmd+1
        // empty ack (handled via BikeProfile.handleUnknownControl):
        const val CMD_OTA_FTP_INFO        = 0x103a0   // {port,userName,pwd} for the FTP OTA server
        const val CMD_MEDIA_FEATURE_CFG   = 0x10020   // {music,talkie,tts,vr,autoChangeToBT} feature flags
        // Lands on the CAR_DATA :10922 socket (with CHECK_SN). Empty 0x104a1 ack; SOCKS on the
        // advertised ports is not required for a stable link (see Nk800 dual-heartbeat fix).
        const val CMD_SOCK_SERVER_INFO     = 0x104a0
        const val CMD_SOCK_SERVER_INFO_ACK = 0x104a1
        // Bike→phone wall-clock / keepalive (~2s). Payload: 16-byte header +
        // "yyyy-MM-dd HH:mm:ss.SSS000000" (29 ASCII). Bikes with supportSyncCorrectTime
        // apply the 0x10601 ack body — empty ack → epoch/1970 on Morini/Voge. Reply with phone time.
        const val CMD_HU_TIME_SYNC         = 0x10600
        const val CMD_HU_TIME_SYNC_ACK     = 0x10601
        // ECP_C2P_QUERY_TIME — bike asks for wall clock (often empty). Empty 0x10451 → 1970/00:00
        // on many HUs. Zontes 125X (channel 21340) gets the OEM JSON; everyone else stays empty.
        const val CMD_HU_QUERY_TIME        = 0x10450
        const val CMD_HU_QUERY_TIME_ACK    = 0x10451

        // PXC application-level commands (sent AFTER channel selection completes).
        const val CMD_CLIENT_INFO         = 65552      // 0x10010   C2P (both directions)
        const val CMD_REMOTE_AUTH_RESULT  = 131088     // 0x20010   P2C
        const val CMD_AUTH_HUID           = 196624     // 0x30010   R2A
        const val CMD_MIRROR_START        = 196640     // 0x30020   R2A
        const val CMD_MIRROR_STOP         = 196656     // 0x30030   R2A
        const val CMD_SCREEN_TOUCH        = 196672     // 0x30040   R2A
        const val CMD_RVINFO              = 1610809424 // 0x60004110 R2A
        const val CMD_SCREEN_EVENT        = 262160     // 0x40010   A2R
        const val CMD_START_OVERLAY       = 262224     // 0x40020   A2R

        fun nameOf(cmd: Int): String = when (cmd) {
            CMD_LOG_REPORT             -> "LOG_REPORT (CFDL26)"
            CMD_LOG_REPORT_ACK         -> "LOG_REPORT_ACK (CFDL26)"
            CMD_OTA_FTP_INFO           -> "OTA_FTP_INFO (CFDL26)"
            CMD_MEDIA_FEATURE_CFG      -> "MEDIA_FEATURE_CFG (CFDL26)"
            CMD_SOCK_SERVER_INFO       -> "SOCK_SERVER_INFO"
            CMD_SOCK_SERVER_INFO_ACK   -> "SOCK_SERVER_INFO_ACK"
            CMD_HU_TIME_SYNC           -> "HU_TIME_SYNC"
            CMD_HU_TIME_SYNC_ACK       -> "HU_TIME_SYNC_ACK"
            CMD_HU_QUERY_TIME          -> "HU_QUERY_TIME"
            CMD_HU_QUERY_TIME_ACK      -> "HU_QUERY_TIME_ACK"
            CMD_CLIENT_INFO            -> "CLIENT_INFO (PXC)"
            CMD_REMOTE_AUTH_RESULT     -> "REMOTE_AUTH_RESULT"
            CMD_AUTH_HUID              -> "AUTH_HUID"
            CMD_MIRROR_START           -> "MIRROR_START (PXC)"
            CMD_MIRROR_STOP            -> "MIRROR_STOP"
            CMD_SCREEN_TOUCH           -> "SCREEN_TOUCH"
            CMD_RVINFO                 -> "RVINFO"
            CMD_SCREEN_EVENT           -> "SCREEN_EVENT"
            CMD_START_OVERLAY          -> "START_OVERLAY"
            CMD_CHANNEL_MCULITE_CTRL   -> "CHANNEL_MCULITE_CTRL"
            CMD_CHANNEL_MCULITE_CTRL + 1 -> "CHANNEL_MCULITE_CTRL_ACK"
            CMD_CHANNEL_MCULITE_DATA   -> "CHANNEL_MCULITE_DATA"
            CMD_CHANNEL_MCULITE_DATA + 1 -> "CHANNEL_MCULITE_DATA_ACK"
            CMD_CHANNEL_MCU_CTRL       -> "CHANNEL_MCU_CTRL"
            CMD_CHANNEL_MCU_DATA       -> "CHANNEL_MCU_DATA"
            CMD_MCU_CLIENT_INFO        -> "MCU_CLIENT_INFO"
            CMD_MCU_START_MIRROR       -> "MCU_START_MIRROR"
            CMD_MCU_CHECK_OTA          -> "MCU_CHECK_OTA"
            CMD_MCU_START_OTA          -> "MCU_START_OTA"
            else                       -> "?"
        }

        /** Reads exactly len bytes or returns false (no data). */
        fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
            var read = 0
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n <= 0) return false
                read += n
            }
            return true
        }

        /** Reads one CmdBaseHead frame from the stream. Returns null on EOF or bad header. */
        fun read(input: InputStream): PxcFrame? {
            val header = ByteArray(16)
            if (!readFully(input, header, 16)) return null
            val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = buf.getInt(0)
            val totalLen = buf.getInt(4)
            val magic = buf.getInt(8)
            if ((cmd xor totalLen) != magic) {
                throw IllegalStateException("bad magic: cmd=$cmd len=$totalLen magic=$magic")
            }
            val payloadLen = (totalLen - 16).coerceAtLeast(0)
            val payload = ByteArray(payloadLen)
            if (payloadLen > 0 && !readFully(input, payload, payloadLen)) {
                throw IllegalStateException("short payload, expected=$payloadLen")
            }
            return PxcFrame(cmd, payload)
        }
    }
}
