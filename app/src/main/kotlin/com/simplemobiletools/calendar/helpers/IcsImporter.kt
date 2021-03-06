package com.simplemobiletools.calendar.helpers

import android.content.Context
import com.simplemobiletools.calendar.R
import com.simplemobiletools.calendar.extensions.dbHelper
import com.simplemobiletools.calendar.helpers.IcsImporter.ImportResult.*
import com.simplemobiletools.calendar.models.Event
import com.simplemobiletools.calendar.models.EventType
import java.io.File

class IcsImporter {
    enum class ImportResult {
        IMPORT_FAIL, IMPORT_OK, IMPORT_PARTIAL
    }

    var curStart = -1
    var curEnd = -1
    var curTitle = ""
    var curDescription = ""
    var curImportId = ""
    var curFlags = 0
    var curReminderMinutes = ArrayList<Int>()
    var curRepeatExceptions = ArrayList<Int>()
    var curRepeatInterval = 0
    var curRepeatLimit = 0
    var curRepeatRule = 0
    var curEventType = DBHelper.REGULAR_EVENT_TYPE_ID
    var curLastModified = 0L
    var isNotificationDescription = false
    var lastReminderAction = ""

    var eventsImported = 0
    var eventsFailed = 0

    fun importEvents(context: Context, path: String, defaultEventType: Int): ImportResult {
        try {
            val importIDs = context.dbHelper.getImportIds()
            var prevLine = ""

            val inputStream = if (path.contains("/")) {
                File(path).inputStream()
            } else {
                context.assets.open(path)
            }

            inputStream.bufferedReader().use {
                while (true) {
                    var line = it.readLine() ?: break
                    if (line.trim().isEmpty())
                        continue

                    if (line.substring(0, 1) == " ") {
                        line = prevLine + line.trim()
                        eventsFailed--
                    }

                    if (line == BEGIN_EVENT) {
                        resetValues()
                        curEventType = defaultEventType
                    } else if (line.startsWith(DTSTART)) {
                        curStart = getTimestamp(line.substring(DTSTART.length))
                    } else if (line.startsWith(DTEND)) {
                        curEnd = getTimestamp(line.substring(DTEND.length))
                    } else if (line.startsWith(DURATION)) {
                        val duration = line.substring(DURATION.length)
                        curEnd = curStart + Parser().parseDurationSeconds(duration)
                    } else if (line.startsWith(SUMMARY) && !isNotificationDescription) {
                        curTitle = line.substring(SUMMARY.length)
                        curTitle = getTitle(curTitle).replace("\\n", "\n")
                    } else if (line.startsWith(DESCRIPTION) && !isNotificationDescription) {
                        curDescription = line.substring(DESCRIPTION.length).replace("\\n", "\n")
                    } else if (line.startsWith(UID)) {
                        curImportId = line.substring(UID.length)
                    } else if (line.startsWith(RRULE)) {
                        val repeatRule = Parser().parseRepeatInterval(line.substring(RRULE.length), curStart)
                        curRepeatRule = repeatRule.repeatRule
                        curRepeatInterval = repeatRule.repeatInterval
                        curRepeatLimit = repeatRule.repeatLimit
                    } else if (line.startsWith(ACTION)) {
                        isNotificationDescription = true
                        lastReminderAction = line.substring(ACTION.length)
                    } else if (line.startsWith(TRIGGER)) {
                        if (lastReminderAction == DISPLAY)
                            curReminderMinutes.add(Parser().parseDurationSeconds(line.substring(TRIGGER.length)) / 60)
                    } else if (line.startsWith(CATEGORIES)) {
                        val categories = line.substring(CATEGORIES.length)
                        tryAddCategories(categories, context)
                    } else if (line.startsWith(LAST_MODIFIED)) {
                        curLastModified = getTimestamp(line.substring(LAST_MODIFIED.length)) * 1000L
                    } else if (line.startsWith(EXDATE)) {
                        curRepeatExceptions.add(getTimestamp(line.substring(EXDATE.length)))
                    } else if (line == END_EVENT) {
                        if (curTitle.isEmpty() || curStart == -1 || curEnd == -1 || importIDs.contains(curImportId))
                            continue

                        if (curImportId.isNotEmpty())
                            importIDs.add(curImportId)

                        val event = Event(0, curStart, curEnd, curTitle, curDescription, curReminderMinutes.getOrElse(0, { -1 }),
                                curReminderMinutes.getOrElse(1, { -1 }), curReminderMinutes.getOrElse(2, { -1 }), curRepeatInterval,
                                curImportId, curFlags, curRepeatLimit, curRepeatRule, curEventType, lastUpdated = curLastModified,
                                source = SOURCE_IMPORTED_ICS)

                        if (event.getIsAllDay() && curEnd > curStart) {
                            event.endTS -= DAY
                        }

                        context.dbHelper.insert(event, true) {
                            for (exceptionTS in curRepeatExceptions) {
                                context.dbHelper.addEventRepeatException(it, exceptionTS)
                            }
                            eventsImported++
                            resetValues()
                        }
                    }
                    prevLine = line
                }
            }
        } catch (e: Exception) {
            eventsFailed++
        }

        return if (eventsImported == 0) {
            IMPORT_FAIL
        } else if (eventsFailed > 0) {
            IMPORT_PARTIAL
        } else {
            IMPORT_OK
        }
    }

    private fun getTimestamp(fullString: String): Int {
        try {
            return if (fullString.startsWith(';')) {
                val value = fullString.substring(fullString.lastIndexOf(':') + 1)
                if (!value.contains("T"))
                    curFlags = curFlags or FLAG_ALL_DAY

                Parser().parseDateTimeValue(value)
            } else {
                Parser().parseDateTimeValue(fullString.substring(1))
            }
        } catch (e: Exception) {
            eventsFailed++
            return -1
        }
    }

    private fun tryAddCategories(categories: String, context: Context) {
        val eventTypeTitle = if (categories.contains(",")) {
            categories.split(",")[0]
        } else {
            categories
        }

        val eventId = context.dbHelper.getEventTypeIdWithTitle(eventTypeTitle)
        if (eventId == -1) {
            val eventType = EventType(0, eventTypeTitle, context.resources.getColor(R.color.color_primary))
            curEventType = context.dbHelper.insertEventType(eventType)
        } else {
            curEventType = eventId
        }
    }

    private fun getTitle(title: String): String {
        return if (title.startsWith(";") && title.contains(":")) {
            title.substring(title.lastIndexOf(':') + 1)
        } else {
            title.substring(1, Math.min(title.length, 80))
        }
    }

    private fun resetValues() {
        curStart = -1
        curEnd = -1
        curTitle = ""
        curDescription = ""
        curImportId = ""
        curFlags = 0
        curReminderMinutes = ArrayList<Int>()
        curRepeatExceptions = ArrayList<Int>()
        curRepeatInterval = 0
        curRepeatLimit = 0
        curRepeatRule = 0
        curEventType = DBHelper.REGULAR_EVENT_TYPE_ID
        curLastModified = 0L
        isNotificationDescription = false
        lastReminderAction = ""
    }
}
