package com.sidbhat.apikeyportal.group;

/**
 * Thrown when a group still holds something. The message is shown to the user and
 * says what is in the way, since the fix is to move those things out first.
 */
public class GroupNotEmptyException extends RuntimeException {

    public GroupNotEmptyException(GroupUsage usage) {
        super("\"" + usage.group().getName() + "\" still has " + describe(usage)
                + ". Move them elsewhere before deleting the group.");
    }

    private static String describe(GroupUsage usage) {
        StringBuilder parts = new StringBuilder();
        append(parts, usage.members(), "member");
        append(parts, usage.keys(), "key");
        append(parts, usage.integrations(), "integration");
        append(parts, usage.invitations(), "pending invitation");
        return parts.toString();
    }

    private static void append(StringBuilder parts, long count, String noun) {
        if (count == 0) {
            return;
        }
        if (!parts.isEmpty()) {
            parts.append(", ");
        }
        parts.append(count).append(' ').append(noun).append(count == 1 ? "" : "s");
    }
}
