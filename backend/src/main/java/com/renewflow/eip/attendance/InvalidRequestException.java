package com.renewflow.eip.attendance;

/** 輸入格式正確、但不符合業務規則（對外 400 INVALID_REQUEST），例如未來時間、下班早於上班。 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
