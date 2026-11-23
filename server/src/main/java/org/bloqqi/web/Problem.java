package org.bloqqi.web;

/**
 * A syntax or semantic error in a Bloqqi file. Line and column are 1-based;
 * 0 means unknown.
 */
public record Problem(int line, int column, String message) {
}
