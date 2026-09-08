package expo.modules.t3agentnotifications

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 33, 36], manifest = Config.NONE)
class AgentNotificationsTest {
  private lateinit var context: Application
  private lateinit var manager: NotificationManager
  private lateinit var lifecycle: LifecycleRegistry

  @Before
  fun setUp() {
    context = RuntimeEnvironment.getApplication()
    manager = context.getSystemService(NotificationManager::class.java)
    shadowOf(manager).setNotificationsEnabled(true)
    lifecycle = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
    lifecycle.currentState = Lifecycle.State.CREATED

    val launcher = ComponentName(context, Activity::class.java)
    shadowOf(context.packageManager).addActivityIfNotPresent(launcher)
    shadowOf(context.packageManager).addIntentFilterForActivity(
      launcher,
      IntentFilter(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
      }
    )
    AgentNotifications.clear(context)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
  }

  private fun update(alertId: String, active: Boolean) = mapOf(
    "device_id" to "device",
    "user_id" to "user",
    "updated_at" to System.currentTimeMillis().toString(),
    "active" to active.toString(),
    "activity_title" to "1 active agent",
    "activity_body" to "Test thread · Working",
    "activity_path" to "/threads/environment/thread",
    "alert_id" to alertId,
    "alert_title" to "Test thread",
    "alert_body" to "Done: Test project",
    "alert_path" to "/threads/environment/thread",
  )

  @Test
  fun foregroundSuppressesAlertsWhileOngoingActivityStillUpdatesAndClears() {
    lifecycle.currentState = Lifecycle.State.RESUMED

    AgentNotifications.receive(context, update("attention", true))

    val ongoing = manager.activeNotifications.single()
    assertEquals("t3-agent-activity", ongoing.tag)
    assertEquals("1 active agent", ongoing.notification.extras.getString(Notification.EXTRA_TITLE))
    assertTrue(ongoing.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)

    AgentNotifications.receive(context, update("completion", false))

    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun backgroundCompletionAlertsAndClearsOngoingActivity() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("running", true))
    lifecycle.currentState = Lifecycle.State.CREATED

    AgentNotifications.receive(context, update("completion", false))

    val alert = manager.activeNotifications.single()
    assertEquals("t3-agent-alert", alert.tag)
    assertEquals("Test thread", alert.notification.extras.getString(Notification.EXTRA_TITLE))
    assertFalse(alert.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
  }

  @Test
  fun retryOfForegroundSuppressedAlertDoesNotAppearAfterBackgrounding() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val suppressed = update("foreground-completion", false)
    AgentNotifications.receive(context, suppressed)
    lifecycle.currentState = Lifecycle.State.CREATED

    AgentNotifications.receive(context, suppressed)

    assertTrue(manager.activeNotifications.isEmpty())

    AgentNotifications.receive(context, update("later-background-completion", false))

    assertEquals("later-background-completion".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun recentSuppressedAlertsStaySilentAfterHistoryRollsOverAndTheAppReopens() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    for (index in 0..79) {
      AgentNotifications.receive(context, update("completion-$index", false))
    }
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    lifecycle.currentState = Lifecycle.State.CREATED
    for (index in 16..79) {
      AgentNotifications.receive(context, update("completion-$index", false))
    }
    assertTrue(manager.activeNotifications.isEmpty())

    AgentNotifications.receive(context, update("new-completion", false))
    assertEquals("new-completion".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun upgradingAlertHistoryPreservesOldRetriesAndStoresNewAlerts() {
    context.getSharedPreferences("t3-agent-notifications", Application.MODE_PRIVATE).edit()
      .putStringSet("seenAlerts", setOf("old-completion", "other-completion")).commit()
    AgentNotifications.receive(context, update("old-completion", false))
    assertTrue(manager.activeNotifications.isEmpty())

    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("new-completion", false))
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    lifecycle.currentState = Lifecycle.State.CREATED
    for (id in listOf("old-completion", "other-completion", "new-completion")) {
      AgentNotifications.receive(context, update(id, false))
    }
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun returningToForegroundSuppressesNewAlertsWithoutRemovingPreviousOnes() {
    AgentNotifications.receive(context, update("background-completion", false))
    lifecycle.currentState = Lifecycle.State.RESUMED

    AgentNotifications.receive(context, update("foreground-completion", false))

    assertEquals("background-completion".hashCode(), manager.activeNotifications.single().id)
  }

  @Test
  fun groupedAlertDisplaysEveryThreadAndRetriesStaySilent() {
    val titles = (1..5).map { "Thread $it " + "x".repeat(111) }.joinToString(", ")
    val grouped = update("group-completion", false) + mapOf(
      "alert_title" to "5 agents finished",
      "alert_body" to titles,
      "alert_path" to "/",
    )

    AgentNotifications.receive(context, grouped)
    AgentNotifications.receive(
      context,
      grouped + ("alert_body" to "A retry must not replace this alert")
    )

    val alert = manager.activeNotifications.single()
    assertEquals("5 agents finished", alert.notification.extras.getString(Notification.EXTRA_TITLE))
    assertEquals(titles, alert.notification.extras.getString(Notification.EXTRA_BIG_TEXT))
    assertEquals("t3code-dev://", shadowOf(alert.notification.contentIntent).savedIntent.dataString)
  }

  @Test
  fun foregroundSuppressedGroupCannotAppearOnBackgroundRetry() {
    val grouped = update("group-attention", true) + mapOf(
      "alert_title" to "2 agents need attention",
      "alert_body" to "First thread, Second thread",
      "alert_path" to "/",
    )
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, grouped)
    lifecycle.currentState = Lifecycle.State.CREATED
    AgentNotifications.receive(context, grouped)

    assertEquals("t3-agent-activity", manager.activeNotifications.single().tag)
  }

  @Test
  fun reopeningSameAccountPreservesCardsDeduplicationAndDismissal() {
    val message = update("attention", true)
    AgentNotifications.receive(context, message)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    assertEquals(2, manager.activeNotifications.size)
    AgentNotifications.dismiss(context)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, message)
    assertEquals("t3-agent-alert", manager.activeNotifications.single().tag)
  }

  @Test
  fun changingAccountOrDeviceClearsOldCardsAndRejectsOldPushes() {
    AgentNotifications.receive(context, update("attention", true))
    AgentNotifications.configure(context, "device", "different-user", "t3code-dev", true)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.configure(context, "different-device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.clear(context)
    AgentNotifications.receive(context, update("attention", true))
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun expandedActivityShowsFiveRowsAndUsesThePriorityThreadRoute() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val lines =
      listOf(
        "Approval: First · Project",
        "Input: Second · Project",
        "Failed: Third · Project",
        "Working: Fourth · Project",
        "Done: Fifth · Project"
      )
    AgentNotifications.receive(
      context,
      update("attention", true) +
        lines.mapIndexed { index, line -> "activity_line_$index" to line }.toMap()
    )
    val card = manager.activeNotifications.single().notification
    assertEquals(lines.joinToString("\n"), card.extras.getString(Notification.EXTRA_BIG_TEXT))
    assertEquals(
      "t3code-dev://threads/environment/thread",
      shadowOf(card.contentIntent).savedIntent.dataString
    )
  }

  @Test
  fun activityActionOpensTheDisplayedThreadWithAnOlderRelay() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("work", true))

    val card = manager.activeNotifications.single().notification
    assertEquals("Open thread", card.actions[0].title.toString())
    assertEquals(card.contentIntent, card.actions[0].actionIntent)
    card.actions[0].actionIntent.send()
    assertEquals(
      "t3code-dev://threads/environment/thread",
      shadowOf(context).nextStartedActivity.dataString
    )
    assertEquals("Dismiss", card.actions[1].title.toString())
    assertEquals(card.deleteIntent, card.actions[1].actionIntent)
  }

  @Test
  fun reviewActionFollowsTheNewPriorityThreadAndClearsWhenItResumes() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val waiting = update("approval", true) + mapOf(
      "activity_title" to "Approval needed",
      "activity_subtext" to "2 active agents",
      "activity_phase" to "waiting_for_approval",
    )
    AgentNotifications.receive(context, waiting)
    val first = manager.activeNotifications.single().notification
    assertEquals("Review", first.actions[0].title.toString())
    assertEquals("2 active agents", first.extras.getString(Notification.EXTRA_SUB_TEXT))

    AgentNotifications.receive(context, waiting + ("activity_path" to "/threads/other/approval"))
    val second = manager.activeNotifications.single().notification
    second.actions[0].actionIntent.send()
    assertEquals(
      "t3code-dev://threads/other/approval",
      shadowOf(context).nextStartedActivity.dataString
    )

    AgentNotifications.receive(context, update("resumed", true) + mapOf(
      "activity_phase" to "running",
      "activity_subtext" to "",
    ))
    val resumed = manager.activeNotifications.single().notification
    assertEquals("Open thread", resumed.actions[0].title.toString())
    assertTrue(resumed.extras.getString(Notification.EXTRA_SUB_TEXT).isNullOrEmpty())
  }

  @Test
  fun inputAndTerminalCardsOfferNavigationWithoutAnApprovalAction() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    for (phase in listOf("waiting_for_input", "completed", "failed", "future_phase")) {
      AgentNotifications.receive(context, update(phase, phase == "waiting_for_input") + mapOf(
        "activity_phase" to phase,
        "activity_expires_at" to (System.currentTimeMillis() + 900000).toString(),
      ))
      val card = manager.activeNotifications.single().notification
      assertEquals("Open thread", card.actions[0].title.toString())
      assertEquals(card.contentIntent, card.actions[0].actionIntent)
    }
  }

  @Test
  fun missingLauncherStillDisplaysAlertsAndOffersActivityDismissal() {
    context.packageManager.setComponentEnabledSetting(
      ComponentName(context, Activity::class.java),
      PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
      PackageManager.DONT_KILL_APP,
    )
    assertEquals(null, context.packageManager.getLaunchIntentForPackage(context.packageName))

    AgentNotifications.receive(context, update("waiting", true) + mapOf(
      "activity_phase" to "waiting_for_approval",
      "activity_title" to "Approval needed",
    ))
    val alert = manager.activeNotifications.single { it.tag == "t3-agent-alert" }.notification
    val card = manager.activeNotifications.single { it.tag == "t3-agent-activity" }.notification
    assertEquals(null, alert.contentIntent)
    assertEquals(null, card.contentIntent)
    assertEquals("Approval needed", card.extras.getString(Notification.EXTRA_TITLE))
    assertEquals("Dismiss", card.actions.single().title.toString())
    AgentActivityDismissReceiver().onReceive(context, shadowOf(card.actions.single().actionIntent).savedIntent)
    assertTrue(manager.activeNotifications.all { it.tag == "t3-agent-alert" })
  }

  @Test
  fun quietWorkUsesAbsoluteRelayLifetimeInsteadOfTenMinuteRemoval() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val expiresAt = System.currentTimeMillis() + 2 * 60 * 60 * 1000L
    AgentNotifications.receive(
      context,
      update("work", true) + ("activity_expires_at" to expiresAt.toString())
    )
    val card = manager.activeNotifications.single().notification
    assertTimeout(card, 119 * 60 * 1000L..120 * 60 * 1000L)
  }

  @Test
  fun finishedCardIsRetainedSilentlyWithoutOngoingFlagAndExpiresAtTheOriginalDeadline() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L
    val finished = update("finished", false) + mapOf(
      "activity_title" to "Agent work failed",
      "activity_body" to "Failed: Test thread · Project",
      "activity_expires_at" to expiresAt.toString(),
    )
    AgentNotifications.receive(context, finished)
    val card = manager.activeNotifications.single().notification
    assertEquals("Agent work failed", card.extras.getString(Notification.EXTRA_TITLE))
    assertFalse(card.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertTimeout(card, 1..15 * 60 * 1000L)
    AgentNotifications.receive(
      context,
      finished + ("activity_expires_at" to (System.currentTimeMillis() - 1).toString())
    )
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun dismissalIncludesFinishedReplaysAndANewRunRearmsTheCard() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.dismiss(context)
    val finished =
      update("finished", false) +
        ("activity_expires_at" to (System.currentTimeMillis() + 900000).toString())
    AgentNotifications.receive(context, finished)
    AgentNotifications.receive(context, finished)
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.receive(context, update("new-work", true))
    assertEquals("t3-agent-activity", manager.activeNotifications.single().tag)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun reorderedActivityDoesNotEraseNewerCardOrDropAnIndependentAlert() {
    val now = System.currentTimeMillis()
    AgentNotifications.receive(context, update("new", true) + ("updated_at" to now.toString()))
    AgentNotifications.receive(
      context,
      update("older-alert", false) + ("updated_at" to (now - 1000).toString())
    )
    assertEquals(3, manager.activeNotifications.size)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    shadowOf(manager).setNotificationsEnabled(false)
    AgentNotifications.receive(context, update("revoked-permission", true))
    assertEquals(3, manager.activeNotifications.size)
  }

  @Test
  fun longRowsKeepStatusAndBothTitlesWithinTheNotificationWidth() {
    lifecycle.currentState = Lifecycle.State.RESUMED
    val raw = "Approval\t${"Long thread name ".repeat(10)}\t${"Project name ".repeat(10)}"
    AgentNotifications.receive(
      context,
      update("long-work", true) + (0..4).associate { "activity_line_$it" to raw }
    )
    val lines = manager.activeNotifications.single().notification.extras.getString(
      Notification.EXTRA_BIG_TEXT
    )!!.split('\n')
    assertEquals(5, lines.size)
    for (line in lines) {
      assertTrue(line.startsWith("Approval: "))
      assertTrue(line.contains(" · "))
      assertTrue(line.length < raw.length)
      assertFalse(line.contains('\t'))
      assertTrue(line.substringAfter(" · ").isNotBlank())
    }
  }

  private fun assertTimeout(card: Notification, expected: LongRange) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      assertTrue(card.timeoutAfter in expected)
      assertTrue(
        shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty()
      )
    } else {
      val alarm = shadowOf(
        context.getSystemService(AlarmManager::class.java)
      ).scheduledAlarms.single()
      assertTrue(alarm.triggerAtTime - System.currentTimeMillis() in expected)
      assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
    }
  }

  @Test
  @Config(sdk = [24, 25])
  fun legacyExpiryRemovesOnlyTheCardAndCannotRemoveANewerRun() {
    val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    val expiresAt = System.currentTimeMillis() + 900000
    AgentNotifications.receive(
      context,
      update("done", false) + ("activity_expires_at" to expiresAt.toString())
    )
    val oldExpiry = alarms.scheduledAlarms.single().operation!!
    AgentNotifications.receive(context, update("next-run", true))
    assertEquals(1, alarms.scheduledAlarms.size)
    val receiver = AgentActivityExpiryReceiver()
    receiver.onReceive(context, shadowOf(oldExpiry).savedIntent)
    AgentNotifications.expire(context, expiresAt + 60_000)
    assertEquals(1, manager.activeNotifications.count { it.tag == "t3-agent-activity" })
    AgentNotifications.expire(context, expiresAt + 2 * 60 * 60 * 1000L)
    assertTrue(manager.activeNotifications.all { it.tag == "t3-agent-alert" })
    assertTrue(alarms.scheduledAlarms.isEmpty())
  }

  @Test
  @Config(sdk = [24, 25])
  fun legacyExpiryIsCancelledOnDismissDisableAndSignOut() {
    val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.dismiss(context)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("work", true))
    assertEquals(1, alarms.scheduledAlarms.size)
    AgentNotifications.configure(context, "device", "user", "t3code-dev", false)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    AgentNotifications.configure(context, "device", "user", "t3code-dev", true)
    AgentNotifications.receive(context, update("work", true))
    AgentNotifications.clear(context)
    assertTrue(alarms.scheduledAlarms.isEmpty())
    assertTrue(manager.activeNotifications.isEmpty())
  }

  @Test
  fun alertsAndActivityUseVersionAppropriatePriorityAndPromotion() {
    AgentNotifications.receive(context, update("work", true))
    val alert = manager.activeNotifications.single { it.tag == "t3-agent-alert" }.notification
    val card = manager.activeNotifications.single { it.tag == "t3-agent-activity" }.notification
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      assertEquals(Notification.PRIORITY_HIGH, alert.priority)
      assertTrue(alert.defaults and Notification.DEFAULT_SOUND != 0)
      assertEquals(Notification.PRIORITY_LOW, card.priority)
      assertEquals(0, card.defaults)
    } else {
      assertEquals(
        NotificationManager.IMPORTANCE_HIGH,
        manager.getNotificationChannel(alert.channelId).importance
      )
      assertEquals(
        NotificationManager.IMPORTANCE_LOW,
        manager.getNotificationChannel(card.channelId).importance
      )
    }
    assertTrue(NotificationCompat.isRequestPromotedOngoing(card))
    assertFalse(NotificationCompat.isRequestPromotedOngoing(alert))
    // Robolectric's API 36 image predates the shipped Live Update rules;
    // its hasPromotableCharacteristics() incorrectly requires colorization.
    assertFalse(card.extras.getBoolean(NotificationCompat.EXTRA_COLORIZED))
    assertTrue(card.flags and Notification.FLAG_ONGOING_EVENT != 0)
    assertEquals(Notification.VISIBILITY_PRIVATE, card.visibility)
  }

  @Test
  fun expiredMalformedAndFutureMessagesCannotDisplayOrPoisonLaterUpdates() {
    val invalid = update("invalid", true)
    AgentNotifications.receive(context, invalid - "updated_at")
    AgentNotifications.receive(context, invalid + ("updated_at" to "invalid"))
    AgentNotifications.receive(
      context,
      invalid + ("updated_at" to (System.currentTimeMillis() - 600001).toString())
    )
    AgentNotifications.receive(
      context,
      invalid + ("updated_at" to (System.currentTimeMillis() + 3600000).toString())
    )
    assertTrue(manager.activeNotifications.isEmpty())
    AgentNotifications.receive(context, update("valid", true))
    assertEquals(2, manager.activeNotifications.size)
  }

  @Test
  fun deniedPermissionDoesNotConsumeAnAlertBeforeTheUserAllowsNotifications() {
    shadowOf(manager).setNotificationsEnabled(false)
    val message = update("attention", true)
    AgentNotifications.receive(context, message)
    assertTrue(manager.activeNotifications.isEmpty())
    shadowOf(manager).setNotificationsEnabled(true)
    AgentNotifications.receive(context, message)
    assertEquals(2, manager.activeNotifications.size)
  }
}
