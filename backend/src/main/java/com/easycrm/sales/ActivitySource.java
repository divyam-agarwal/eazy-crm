package com.easycrm.sales;

/**
 * Whether a human logged this row or the system did. SYSTEM rows are never editable —
 * they are a record of something the application itself observed, and letting a user
 * rewrite one would make the log unreliable exactly where it is most trustworthy.
 */
public enum ActivitySource { MANUAL, SYSTEM }
