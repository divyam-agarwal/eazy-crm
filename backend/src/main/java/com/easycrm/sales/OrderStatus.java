package com.easycrm.sales;

public enum OrderStatus {
    CONFIRMED, DISPATCHED, CLOSED, CANCELLED;

    public boolean isTerminal() { return this == CLOSED || this == CANCELLED; }
    public boolean isActive()   { return !isTerminal(); }
}
