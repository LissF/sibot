package ua.org.tenletters.bot.sibot

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

data class Update(
        val update_id: Long,
        val message: Message?
)

data class Message(
        val message_id: Long,
        val from: User?,
        val chat: Chat,
        val text: String?
)

data class User(
        val id: Long,
        val is_bot: Boolean,
        val first_name: String,
        val last_name: String?,
        val username: String?,
        val language_code: String?
)

data class Chat(
        val id: Long
)

data class ReplyKeyboardMarkup(
        val keyboard: Array<Array<KeyboardButton>>,
        val resize_keyboard: Boolean
) {
    override fun toString(): String = jacksonObjectMapper().writeValueAsString(this)
}

data class KeyboardButton(
        val text: String
)