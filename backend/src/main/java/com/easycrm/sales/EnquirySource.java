package com.easycrm.sales;

// Own copy of the six lead sources; keeps `sales` decoupled from `crm.CustomerSource`
// (each aggregate owns its enum, as QuotationStatus/OrderStatus/VersionStatus do).
public enum EnquirySource { INDIAMART, WHATSAPP, PHONE, REFERRAL, MANUAL, IMPORT }
