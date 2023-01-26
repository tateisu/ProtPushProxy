package jp.juggler.pushreceiverapp.notification

import jp.juggler.pushreceiverapp.R

fun notificationTypeToIconId(type: String?) = when (type) {
    "favourite" -> R.drawable.baseline_star_24
    "mention" -> R.drawable.baseline_alternate_email_24
    "reply" -> R.drawable.baseline_reply_24
    "reblog", "renote" -> R.drawable.baseline_repeat_24
    "quote" -> R.drawable.baseline_format_quote_24
    "follow", "unfollow" -> R.drawable.baseline_person_add_24
    "reaction", "emoji_reaction", "pleroma:emoji_reaction" ->
        R.drawable.baseline_pets_24

    "follow_request", "receiveFollowRequest", "followRequestAccepted" ->
        R.drawable.baseline_manage_accounts_24

    "pollVote", "poll_vote", "poll" ->
        R.drawable.baseline_poll_24

    "status", "update", "status_reference" ->
        R.drawable.baseline_edit_note_24

    "admin.sign_up" ->
        R.drawable.baseline_add_24
    else -> R.drawable.nc_error
}
