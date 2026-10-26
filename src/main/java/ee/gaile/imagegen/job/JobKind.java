package ee.gaile.imagegen.job;

public enum JobKind {
    /** Text to image */
    IMAGE,
    /** Edit of an uploaded photo by a plain instruction */
    EDIT,
    /** Redraw of the masked area of an uploaded photo */
    INPAINT,
    /** Video from a start image */
    VIDEO
}
