package com.example.veilark.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.veilark.R
import com.example.veilark.profile.ConnectionNode
import com.example.veilark.profile.ProfileSelection
import com.example.veilark.theme.VeilarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainScreenAccessibilityInstrumentedTest {
  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun profileChoicesExposeRadioStateAndKeepSelectionInSync() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    var selectedTag by mutableStateOf(ProfileSelection.AUTOMATIC_TAG)
    val nodeTitle = "Netherlands"

    composeRule.setContent {
      VeilarkTheme(dynamicColor = false) {
        MainScreen(
          profileName = "Test profile",
          connectionNodes = listOf(
            ConnectionNode(tag = "netherlands", name = nodeTitle, protocol = "Trojan"),
          ),
          selectedNodeTag = selectedTag,
          onSelectNode = { selectedTag = it },
        )
      }
    }

    composeRule.onNodeWithText("Test profile").performClick()
    val nodeChoice = hasAnyDescendant(hasText(nodeTitle)).and(
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )
    composeRule.onNode(nodeChoice)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
      .performClick()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))

    composeRule.onNodeWithText(context.getString(R.string.automatic))
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
  }

  @Test
  fun routingChoicesExposeRadioState() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val russiaDirect = context.getString(R.string.russia_direct)

    composeRule.setContent {
      VeilarkTheme(dynamicColor = false) {
        MainScreen(profileName = "Test profile")
      }
    }

    composeRule.onNodeWithText(context.getString(R.string.routing)).performClick()
    val routingChoice = hasAnyDescendant(hasText(russiaDirect)).and(
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
    )
    composeRule.onNode(routingChoice)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
      .performClick()
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
  }
}
