package ee.gaile.imagegen.model;

public enum ParamType {
    TEXT,
    INT,
    FLOAT,
    /** One of the options */
    ENUM,
    /** A number from 0 to 2^31-1, empty = random */
    SEED
}
