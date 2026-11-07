package dev.oxype.client.ui.groupinfo

import dev.oxype.client.data.oxype.GroupInfo
import dev.oxype.client.data.oxype.SessionMember
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one line under the group name.
 *
 * The role is stated once here and derived from the same ids the member rows use, so a
 * wrong subtitle would contradict a label further down the same screen.
 */
class GroupInfoPresentationTest {

    private fun info(
        ownerId: Long = 2L,
        adminIds: List<Long> = listOf(2L),
        members: List<SessionMember> = listOf(
            SessionMember(1L, "alice"),
            SessionMember(2L, "bob")
        ),
        isOwner: Boolean = false,
        isAdmin: Boolean = false
    ) = GroupInfo(
        sessionId = 42L,
        name = "周末徒步小队",
        description = "",
        ownerId = ownerId,
        adminIds = adminIds,
        members = members,
        avatarUrl = null,
        isOwner = isOwner,
        isAdmin = isAdmin
    )

    @Test
    fun `a plain member sees only the member count`() {
        assertEquals("群聊 · 2 位成员", groupInfoSubtitle(info()))
    }

    @Test
    fun `an owner is announced as the owner`() {
        assertEquals("群聊 · 2 位成员 · 我是群主", groupInfoSubtitle(info(isOwner = true, isAdmin = true)))
    }

    @Test
    fun `an admin who is not the owner is announced as an admin`() {
        assertEquals(
            "群聊 · 2 位成员 · 我是管理员",
            groupInfoSubtitle(info(isOwner = false, isAdmin = true))
        )
    }

    @Test
    fun `an empty roster still reads as a group`() {
        assertEquals(
            "群聊 · 0 位成员",
            groupInfoSubtitle(info(ownerId = 0L, adminIds = emptyList(), members = emptyList()))
        )
    }
}
