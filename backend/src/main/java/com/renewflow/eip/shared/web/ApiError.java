package com.renewflow.eip.shared.web;

/** 所有錯誤回應的統一格式。code 給程式判斷，message 給人看；不放堆疊、SQL 或內部細節。 */
public record ApiError(String code, String message) {
}
