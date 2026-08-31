package app.threadmind.domain

data class ActionFieldSpec(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val providerManaged: Boolean = false,
)

fun actionFieldSpecs(type: ActionType): List<ActionFieldSpec> = when (type) {
    ActionType.CREATE_MEETING -> listOf(
        ActionFieldSpec("title", "标题", required = true),
        ActionFieldSpec("startsAt", "开始时间（ISO 8601）", required = true),
        ActionFieldSpec("endsAt", "结束时间（ISO 8601）", required = true),
        ActionFieldSpec("timezone", "时区", required = true),
        ActionFieldSpec("location", "地点或线上会议信息"),
        ActionFieldSpec("attendees", "参与人邮箱（逗号分隔）"),
        ActionFieldSpec("notes", "备注"),
        ActionFieldSpec("targetCalendarId", "目标日历 ID", required = true),
    )
    ActionType.CREATE_CONTACT -> listOf(
        ActionFieldSpec("displayName", "姓名", required = true),
        ActionFieldSpec("contactMethod", "主要电话或邮箱", required = true),
        ActionFieldSpec("email", "其他邮箱"),
        ActionFieldSpec("phone", "其他电话"),
        ActionFieldSpec("company", "公司"),
        ActionFieldSpec("jobTitle", "职位"),
        ActionFieldSpec("address", "地址"),
        ActionFieldSpec("notes", "备注"),
        ActionFieldSpec("accountType", "联系人账户类型", providerManaged = true),
    )
    ActionType.UPDATE_CONTACT -> listOf(
        ActionFieldSpec("contactQuery", "联系人查询条件"),
        ActionFieldSpec("targetContactId", "目标联系人 ID", required = true, providerManaged = true),
        ActionFieldSpec("changes", "已审核字段差异", required = true, providerManaged = true),
    )
}
