package dev.drosh.ime.latin.utils

import android.content.Context
import android.content.Intent
import dev.drosh.ime.keyboard.internal.keyboard_parser.floris.KeyCode
import dev.drosh.ime.latin.inputlogic.InputLogic
import dev.drosh.ime.latin.utils.Log.i

object IntentUtils {
    val TAG: String = InputLogic::class.java.simpleName
    private const val ACTION_SEND_INTENT = "dev.drosh.ime.latin.ACTION_SEND_INTENT"
    private const val EXTRA_NUMBER = "EXTRA_NUMBER"

    @JvmStatic
    fun handleSendIntentKey(context: Context, mKeyCode: Int) {
        val intentNumber = (KeyCode.SEND_INTENT_ONE + 1) - mKeyCode

        val intent: Intent = Intent(ACTION_SEND_INTENT).apply {
            putExtra(EXTRA_NUMBER, intentNumber)
        }

        context.sendBroadcast(intent)
        i(TAG, "Sent broadcast for intent number: $intentNumber")
    }

    /** uses [type] with fallback to generic *\* if there is no activity */
    fun getResolvableTypeIntent(context: Context, type: String): Intent {
        val intent = Intent()
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = type
        if (intent.resolveActivity(context.packageManager) != null)
            return intent
        intent.type = "*/*"
        return intent
    }
}
