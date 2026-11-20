package com.example.hms.enums;

/**
 * Billing-money plan PR 10 (V309): the till a money movement is taken at. It follows the endpoint: an invoice payment
 * or a refund paid out at the billing desk is BILLING, a check-in copay (or a refund a receptionist hands back) is
 * RECEPTION, a dispense payment or a counter sale is PHARMACY. A user with two roles has one session per till.
 */
public enum CashTill {
    BILLING,
    RECEPTION,
    PHARMACY
}
