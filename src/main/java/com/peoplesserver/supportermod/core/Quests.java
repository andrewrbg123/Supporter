package com.peoplesserver.supportermod.core;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The quest catalogue: what can be asked of a player, and which quests are live today.
 *
 * <p><b>Every objective here is tracked with machinery this plugin already has</b> — the 60s
 * quest tick (playtime, distance, days), and the chat handler (messages). Nothing needs a new
 * Hytale event, and nothing ever will touch combat, claims, KOTJ or bucks: the pay-to-win line
 * applies to quest <em>objectives</em> the same as everything else. "Kill 10 players" is not a
 * quest this plugin can ever offer.
 *
 * <p><b>The roster is deterministic, not stored.</b> Everyone gets the same three dailies and
 * the same weekly, chosen by seeding a shuffle with the UTC date (ISO week for the weekly).
 * That survives restarts with no roster table, and makes quests communal — "today's quests"
 * is one conversation, not a per-player roll.
 *
 * <p>Quest keys embed the date ({@code d:2026-08-19:play30}, {@code w:2026-W34:wdays4}), so a
 * new day simply starts writing new progress rows and yesterday's become inert history. Reset
 * is midnight UTC — the same clock the reconcile job runs on.
 */
public final class Quests {

    private Quests() {}

    /** What a quest counts. Progress arrives from the tick (minutes, blocks, days) or chat. */
    public enum Unit { MINUTES, BLOCKS, MESSAGES, DAYS }

    /** One quest definition. Target is in the unit's terms. */
    public record Def(String id, String label, int target, Unit unit) {}

    /** How many dailies are live at once. */
    public static final int DAILY_COUNT = 3;

    private static final List<Def> DAILY_POOL = List.of(
            new Def("login", "Log in today", 1, Unit.DAYS),
            new Def("play30", "Play for 30 minutes", 30, Unit.MINUTES),
            new Def("play60", "Play for an hour", 60, Unit.MINUTES),
            new Def("travel500", "Travel 500 blocks", 500, Unit.BLOCKS),
            new Def("travel1500", "Travel 1,500 blocks", 1500, Unit.BLOCKS),
            new Def("chat3", "Say 3 things in chat", 3, Unit.MESSAGES));

    private static final List<Def> WEEKLY_POOL = List.of(
            new Def("wplay240", "Play 4 hours this week", 240, Unit.MINUTES),
            new Def("wtravel8000", "Travel 8,000 blocks this week", 8000, Unit.BLOCKS),
            new Def("wdays4", "Play on 4 different days this week", 4, Unit.DAYS));

    /** The UTC date key, e.g. {@code 2026-08-19}. Also the marker day-quests dedupe on. */
    public static String dayKey(LocalDate date) {
        return date.toString();
    }

    /** The ISO week key, e.g. {@code 2026-W34}. */
    public static String weekKey(LocalDate date) {
        return date.get(IsoFields.WEEK_BASED_YEAR) + "-W"
                + date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** Today's three dailies — same three for everyone, stable across restarts. */
    public static List<Def> dailyRoster(LocalDate date) {
        List<Def> pool = new ArrayList<>(DAILY_POOL);
        Collections.shuffle(pool, new Random(dayKey(date).hashCode() * 31L + 7));
        return List.copyOf(pool.subList(0, DAILY_COUNT));
    }

    /** This week's quest. */
    public static Def weeklyQuest(LocalDate date) {
        return WEEKLY_POOL.get(Math.floorMod(weekKey(date).hashCode(), WEEKLY_POOL.size()));
    }

    /** The storage key for a daily quest on {@code date}. */
    public static String dailyKey(LocalDate date, Def def) {
        return "d:" + dayKey(date) + ":" + def.id();
    }

    /** The storage key for the weekly quest of {@code date}'s week. */
    public static String weeklyKey(LocalDate date, Def def) {
        return "w:" + weekKey(date) + ":" + def.id();
    }
}
