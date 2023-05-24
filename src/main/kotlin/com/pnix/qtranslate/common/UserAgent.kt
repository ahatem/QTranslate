package com.pnix.qtranslate.common

import kotlin.random.Random

object UserAgent {
  private val USER_AGENTS by lazy {
    javaClass.classLoader.getResourceAsStream("data/user_agents.txt")
      ?.bufferedReader().use { it?.readLines()?.map { line -> line.trim('\"', ',') } ?: emptyList() }
  }

  fun random(): String {
    return USER_AGENTS[Random.nextInt(USER_AGENTS.size)]
  }
}