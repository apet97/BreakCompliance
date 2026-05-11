package me.apet97.breakcompliance.api;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import me.apet97.breakcompliance.addon.auth.NormalizedClaims;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RequestValidator {

    public static void requireAdmin(NormalizedClaims claims) {
        if (claims == null || !claims.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin role required.");
        }
    }

    public static DateRange parseAndValidateDates(String fromIso, String toIso) {
        try {
            if (fromIso == null || fromIso.isBlank() || toIso == null || toIso.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing date boundaries.");
            }
            LocalDate from = LocalDate.parse(fromIso);
            LocalDate to = LocalDate.parse(toIso);
            
            if (from.isAfter(to)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from date must be before or equal to to date.");
            }
            if (ChronoUnit.DAYS.between(from, to) > 90) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date range must not exceed 90 days.");
            }
            return new DateRange(from, to);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid date format. Expected YYYY-MM-DD.");
        }
    }

    public record DateRange(LocalDate from, LocalDate to) {}
}
