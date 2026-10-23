package com.mark.tvremote.core

/** Android TV key codes (the standard android.view.KeyEvent values used by the remote protocol). */
object Key {
    const val HOME = 3
    const val BACK = 4
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val DEL = 67
    const val MENU = 82
    const val SEARCH = 84
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MUTE = 91
    const val SETTINGS = 176
    const val TV_INPUT = 178
    const val ALL_APPS = 284
}

/** Remote key direction values from the protocol. */
object Direction {
    const val START_LONG = 1
    const val END_LONG = 2
    const val SHORT = 3
}

/** Polo pairing protocol (OuterMessage in polo.proto). */
internal object Polo {
    private const val STATUS_OK = 200
    private const val ROLE_INPUT = 1
    private const val ENCODING_HEXADECIMAL = 3
    private const val CODE_LENGTH = 6

    private fun outer(configure: ProtoWriter.() -> Unit) = ProtoWriter()
        .int(1, 2) // protocol_version
        .int(2, STATUS_OK)
        .apply(configure)
        .toByteArray()

    private fun encoding() = ProtoWriter().int(1, ENCODING_HEXADECIMAL).int(2, CODE_LENGTH)

    fun pairingRequest(clientName: String) = outer {
        message(10, ProtoWriter().string(1, "atvremote").string(2, clientName))
    }

    fun options() = outer {
        message(20, ProtoWriter().message(1, encoding()).int(3, ROLE_INPUT))
    }

    fun configuration() = outer {
        message(30, ProtoWriter().message(1, encoding()).int(2, ROLE_INPUT))
    }

    fun secret(secret: ByteArray) = outer { message(40, ProtoWriter().bytes(1, secret)) }

    const val FIELD_PAIRING_REQUEST_ACK = 11
    const val FIELD_OPTIONS = 20
    const val FIELD_CONFIGURATION_ACK = 31
    const val FIELD_SECRET_ACK = 41

    fun isOk(msg: ProtoMessage) = msg.int(2) == STATUS_OK
}

/** Remote control protocol (RemoteMessage in remotemessage.proto). */
internal object RemoteMsg {
    // Feature flags: ping, key, ime, power, volume, app link.
    const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512

    fun configure(code1: Int) = ProtoWriter().message(
        1,
        ProtoWriter().int(1, code1).message(
            2,
            ProtoWriter().int(3, 1).string(4, "1").string(5, "atvremote").string(6, "1.0.0"),
        ),
    ).toByteArray()

    fun setActive(active: Int) = ProtoWriter().message(2, ProtoWriter().int(1, active)).toByteArray()

    fun pingResponse(value: Int) = ProtoWriter().message(9, ProtoWriter().int(1, value)).toByteArray()

    fun keyInject(keyCode: Int, direction: Int) =
        ProtoWriter().message(10, ProtoWriter().int(1, keyCode).int(2, direction)).toByteArray()

    fun text(text: String, imeCounter: Int, fieldCounter: Int): ByteArray {
        val pos = (text.length - 1).coerceAtLeast(0)
        val edit = ProtoWriter()
            .int(1, 1) // insert
            .message(2, ProtoWriter().int(1, pos).int(2, pos).string(3, text))
        return ProtoWriter().message(
            21,
            ProtoWriter().int(1, imeCounter).int(2, fieldCounter).message(3, edit),
        ).toByteArray()
    }

    fun appLink(link: String) = ProtoWriter().message(90, ProtoWriter().string(1, link)).toByteArray()

    const val FIELD_CONFIGURE = 1
    const val FIELD_SET_ACTIVE = 2
    const val FIELD_ERROR = 3
    const val FIELD_PING_REQUEST = 8
    const val FIELD_IME_KEY_INJECT = 20
    const val FIELD_IME_BATCH_EDIT = 21
    const val FIELD_IME_SHOW_REQUEST = 22
    const val FIELD_START = 40
    const val FIELD_VOLUME = 50
}
