package com.renewflow.eip.identity;

/** Session 還在，但它代表的員工已不存在或已停用。對外一律視為「未登入」。 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("unauthenticated");
    }
}
