package io.github.signalbot.application.trigger;

/** A protective exit that bypasses Claude and the normal risk gates. */
public enum ExitType {
    EXIT_STOP,
    EXIT_TP
}
