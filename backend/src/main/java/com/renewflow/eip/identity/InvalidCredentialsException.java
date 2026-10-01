package com.renewflow.eip.identity;

/** 帳號不存在、密碼錯誤、帳號停用的共同結果。刻意不區分原因，避免被用來探測帳號是否存在。 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("invalid credentials");
    }
}
