package com.wallet.dto;

import java.util.List;

public record ValidationErrorResponse(String error, List<String> details) {
}
