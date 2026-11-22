package org.opentron.backend.security.confidential.gateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Conservative rules for common contact and structured identifier formats. */
public final class PrototypeRuleBasedPiiDetector implements PiiDetector {
    private static final Pattern EMAIL = Pattern.compile(
            "(?<![\\p{L}\\p{N}.+-])[\\p{L}\\p{N}_!#$%&'*+/=?^`{|}~-]+(?:\\.[\\p{L}\\p{N}_!#$%&'*+/=?^`{|}~-]+)*@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?(?:\\.[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?)+(?![\\p{L}\\p{N}.-])");
    private static final Pattern SOCIAL_SECURITY_NUMBER = Pattern.compile(
            "(?<!\\d)(?!000|666|9\\d\\d)\\d{3}[- ]?(?!00)\\d{2}[- ]?(?!0000)\\d{4}(?!\\d)");
    private static final Pattern CREDIT_CARD_CANDIDATE = Pattern.compile(
            "(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");
    private static final Pattern IBAN_CANDIDATE = Pattern.compile(
            "(?<![A-Z0-9])[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "(?<![\\p{L}\\d])(?:\\+|00)?(?:\\(\\d{1,4}\\)|\\d)(?:[\\d\\s().-]{5,24}\\d)(?![\\p{L}\\d])");

    @Override
    public String version() {
        return "prototype-rules-2";
    }

    @Override
    public List<DetectedEntity> detect(CharSequence text) {
        if (text == null) {
            throw new IllegalArgumentException("Text must not be null.");
        }

        List<DetectedEntity> entities = new ArrayList<>();
        collect(EMAIL.matcher(text), "EMAIL", entities);
        collect(SOCIAL_SECURITY_NUMBER.matcher(text), "SSN", entities);
        collectValidated(CREDIT_CARD_CANDIDATE.matcher(text), "CREDIT_CARD", entities,
                PrototypeRuleBasedPiiDetector::isLuhnValid, text);
        collectValidated(IBAN_CANDIDATE.matcher(text), "IBAN", entities,
                PrototypeRuleBasedPiiDetector::isIbanValid, text);
        collectValidated(PHONE_CANDIDATE.matcher(text), "PHONE_NUMBER", entities,
                (candidate, start, end, existing) -> {
                    int digits = countDigits(candidate, start, end);
                    return digits >= 8 && digits <= 15
                            && existing.stream().noneMatch(entity ->
                                    entity.start() < end && entity.end() > start);
                }, text);
        entities.sort(Comparator.comparingInt(DetectedEntity::start)
                .thenComparingInt(DetectedEntity::end));

        int previousEnd = -1;
        for (DetectedEntity entity : entities) {
            if (entity.start() < previousEnd) {
                throw new IllegalStateException("PII detector returned overlapping entities.");
            }
            previousEnd = entity.end();
        }
        return List.copyOf(entities);
    }

    private static void collect(
            Matcher matcher, String type, List<DetectedEntity> entities) {
        while (matcher.find()) {
            entities.add(new DetectedEntity(type, matcher.start(), matcher.end()));
        }
    }

    private static void collectValidated(
            Matcher matcher,
            String type,
            List<DetectedEntity> entities,
            CandidateValidator validator,
            CharSequence text) {
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (validator.isValid(text, start, end, entities)) {
                entities.add(new DetectedEntity(type, start, end));
            }
        }
    }

    private static int countDigits(CharSequence value, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            if (Character.isDigit(value.charAt(index))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isLuhnValid(
            CharSequence candidate, int start, int end, List<DetectedEntity> ignored) {
        int checksum = 0;
        int digitPosition = 0;
        for (int index = end - 1; index >= start; index--) {
            char value = candidate.charAt(index);
            if (!Character.isDigit(value)) {
                continue;
            }
            int digit = Character.digit(value, 10);
            if ((digitPosition & 1) == 1) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            checksum += digit;
            digitPosition++;
        }
        return digitPosition >= 13 && digitPosition <= 19 && checksum % 10 == 0;
    }

    private static boolean isIbanValid(
            CharSequence candidate, int start, int end, List<DetectedEntity> ignored) {
        int length = 0;
        for (int index = start; index < end; index++) {
            if (candidate.charAt(index) != ' ') {
                length++;
            }
        }
        if (length < 15 || length > 34) {
            return false;
        }

        int remainder = 0;
        int position = 0;
        for (int index = 0; index < 4; index++) {
            char value = nextIbanCharacter(candidate, start, end, position++);
            if ((index < 2 && !isAsciiLetter(value))
                    || (index >= 2 && !Character.isDigit(value))) {
                return false;
            }
        }
        for (int index = 4; index < length; index++) {
            char value = nextIbanCharacter(candidate, start, end, position++);
            remainder = appendIbanRemainder(remainder, value);
        }
        for (int index = 0; index < 4; index++) {
            char value = nextIbanCharacter(candidate, start, end, index);
            remainder = appendIbanRemainder(remainder, value);
        }
        return remainder == 1;
    }

    private static int appendIbanRemainder(int remainder, char value) {
        if (Character.isDigit(value)) {
            return (remainder * 10 + Character.digit(value, 10)) % 97;
        }
        if (isAsciiLetter(value)) {
            int expanded = value - 'A' + 10;
            return (remainder * 100 + expanded) % 97;
        }
        throw new IllegalStateException("IBAN candidate contains an invalid character.");
    }

    private static char nextIbanCharacter(
            CharSequence candidate, int start, int end, int position) {
        int current = 0;
        for (int index = start; index < end; index++) {
            char value = candidate.charAt(index);
            if (value == ' ') {
                continue;
            }
            if (current++ == position) {
                return Character.toUpperCase(value);
            }
        }
        throw new IllegalStateException("IBAN candidate offsets are inconsistent.");
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z';
    }

    @FunctionalInterface
    private interface CandidateValidator {
        boolean isValid(
                CharSequence candidate, int start, int end, List<DetectedEntity> existing);
    }
}
