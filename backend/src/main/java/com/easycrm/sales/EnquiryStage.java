package com.easycrm.sales;

public enum EnquiryStage {
    NEW, CONTACTED, QUALIFIED, CONVERTED, LOST;

    public boolean isTerminal() { return this == CONVERTED || this == LOST; }
    public boolean isActive()   { return !isTerminal(); }
}
