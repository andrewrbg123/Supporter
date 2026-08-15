#!/usr/bin/env bash
# Phase 0 recon against the Hytale server jar.
#
#   bash tools/recon.sh /path/to/server.jar
#
# Writes recon-output.txt — a small text file, safe to upload anywhere — answering the
# questions PHASE0-RECON.md still has open, and tells you which route to use for getting
# the jar itself across. Read-only: it never modifies the server jar.

set -uo pipefail

JAR="${1:-}"
OUT="recon-output.txt"

if [[ -z "$JAR" || ! -f "$JAR" ]]; then
    echo "usage: bash tools/recon.sh /path/to/server.jar" >&2
    exit 1
fi

if ! command -v javap >/dev/null 2>&1; then
    echo "javap not found — it ships with any JDK. Point PATH at your server's JDK 25." >&2
    exit 1
fi

# javap exits non-zero for a class that is not in the jar; that is a real answer, not a
# failure, so record it and carry on.
dump() {
    echo "--- javap $1"
    javap -cp "$JAR" "$1" 2>&1 | grep -v '^Picked up' || echo "  (not present in jar)"
    echo
}

list() {
    echo "--- entries matching: $1"
    unzip -l "$JAR" | grep -iE "$1" | awk '{print $4}' | head -40 || true
    echo
}

{
    echo "SupporterMod Phase 0 recon"
    echo "jar:   $JAR"
    echo "bytes: $(wc -c <"$JAR")"
    echo "java:  $(java -version 2>&1 | grep -v '^Picked up' | head -1)"
    echo
    echo "=============================================================="
    echo "R3 — player lifecycle and connection-stage events"
    echo "  Looking for anything that fires BEFORE world entry and can"
    echo "  reject or hold a connection. Decides perk 10 (priority queue)."
    echo "=============================================================="
    list 'event.*(player|connect|login|join|session|handshake)\.class'
    dump 'com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent'

    echo "=============================================================="
    echo "PlayerChatEvent — cancellation semantics. Gates all Phase 2"
    echo "  chat code. Need to know whether a later handler can see that"
    echo "  an earlier one cancelled."
    echo "=============================================================="
    dump 'com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent'
    dump 'com.hypixel.hytale.event.EventRegistration'
    dump 'com.hypixel.hytale.event.EventRegistry'

    echo "=============================================================="
    echo "R1 — tab list / player list / scoreboard / nameplate"
    echo "=============================================================="
    list 'tablist|playerlist|player_list|scoreboard|nameplate|namet[ae]g'

    echo "=============================================================="
    echo "R4 — server-side display name. Decides perk 11 (/nick)."
    echo "  Looking for a setter on PlayerRef or a display-name component."
    echo "=============================================================="
    dump 'com.hypixel.hytale.server.core.universe.PlayerRef'
    list 'displayname|display_name'

    echo "=============================================================="
    echo "A5 — full Message API, for the Phase 2 chat line"
    echo "=============================================================="
    dump 'com.hypixel.hytale.server.core.Message'

    echo "=============================================================="
    echo "C9 — player cap, readable at runtime?"
    echo "=============================================================="
    list 'serverconfig|serverproperties|maxplayer|slots'

    echo "=============================================================="
    echo "Scheduler — is there a server-provided one? FactionMod rolls"
    echo "  its own ScheduledExecutorService, which may just be habit."
    echo "=============================================================="
    list 'scheduler|tickservice|repeatingtask'

    echo "=============================================================="
    echo "Universe — offline username lookup? Decides whether Tebex"
    echo "  grants for never-logged-in players need the pending queue."
    echo "=============================================================="
    dump 'com.hypixel.hytale.server.core.universe.Universe'

    echo "=============================================================="
    echo "Command API — confirming the console path for /supporter grant"
    echo "=============================================================="
    dump 'com.hypixel.hytale.server.core.command.system.CommandContext'
    dump 'com.hypixel.hytale.server.core.command.system.basecommands.CommandBase'
} >"$OUT" 2>&1

echo "wrote $OUT ($(wc -c <"$OUT") bytes) — upload this."
echo

# --- sizing for the jar transfer -----------------------------------------------------
raw=$(wc -c <"$JAR")
mb() { awk -v b="$1" 'BEGIN{printf "%.1f", b/1048576}'; }
echo "server jar: $(mb "$raw") MB"

tmp=$(mktemp -d)
if unzip -q "$JAR" '*.class' -d "$tmp" 2>/dev/null && [ -n "$(ls -A "$tmp" 2>/dev/null)" ]; then
    (cd "$tmp" && zip -qr9 "$OLDPWD/server-classes.zip" .) 2>/dev/null
    if [ -f server-classes.zip ]; then
        stripped=$(wc -c <server-classes.zip)
        echo "classes only: $(mb "$stripped") MB  -> server-classes.zip"
        if [ "$stripped" -lt 25000000 ]; then
            echo
            echo "  Under 25 MB: drag server-classes.zip straight into the GitHub web"
            echo "  uploader. That is all I need for the compile classpath."
        else
            echo
            echo "  Over 25 MB. Either push with git (100 MB limit, not 25):"
            echo "     git checkout -b server-jar"
            echo "     mkdir -p libs && cp server-classes.zip libs/"
            echo "     git add -f libs/server-classes.zip && git commit -m 'server classes'"
            echo "     git push -u origin server-jar"
            echo "  or split it for the web uploader:"
            echo "     split -b 24m server-classes.zip server-classes.zip.part-"
            echo "     sha256sum server-classes.zip"
        fi
    fi
else
    echo "classes only: could not extract (no .class entries?)"
fi
rm -rf "$tmp"
