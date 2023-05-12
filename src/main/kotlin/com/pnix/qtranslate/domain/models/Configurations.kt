package com.pnix.qtranslate.domain.models

import java.util.prefs.Preferences
import kotlin.properties.Delegates


object Configurations {

  private val prefs = Preferences.userRoot().node("QTranslate")

  var spellChecking by Delegates.observable(prefs.getBoolean("spell_checking", false))
  { _, _, newValue -> prefs.putBoolean("spell_checking", newValue) }

  var instantTranslation by Delegates.observable(prefs.getBoolean("instant_translation", false))
  { _, _, newValue -> prefs.putBoolean("instant_translation", newValue) }



}