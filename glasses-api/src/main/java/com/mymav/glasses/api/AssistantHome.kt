package com.mymav.glasses.api

/**
 * Smart-home requests. MyMav has no smart-home hub connected (no Home Assistant, SmartThings or similar account), so
 * there are no real devices to show or control. Say that, and never pretend an action happened.
 */
internal object AssistantHome {
    private val RE = Regex(
        "\\b(turn|switch) (on|off) (the |my )?(\\w+ ){0,2}(light|lights|lamp|heating|heater|thermostat|tv|fan|plug|socket|kettle|blinds|curtains)\\b|" +
            "\\b(lock|unlock|open|close) (the |my )?(front |back |garage )?(door|garage|gate|lock)\\b|" +
            "\\bset (the |my )?(thermostat|heating|temperature)\\b|\\bdim (the |my )?\\w* ?lights?\\b|\\b(is|are) (the |my )?(front |back )?(door|lights?|garage) (locked|on|off|open|closed)\\b",
    )

    fun isRequest(c: String): Boolean = RE.containsMatchIn(c)

    fun outcome() = AssistantRouter.Outcome(
        "I can't control your home yet: no smart-home hub is connected to MyMav, so I have no devices to show and I won't pretend anything happened. It needs an account link such as Home Assistant.",
        AssistantLog.Kind.Error, emptyList(), "",
    )
}
