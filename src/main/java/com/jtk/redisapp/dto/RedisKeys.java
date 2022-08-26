package com.jtk.redisapp.dto;

public enum RedisKeys {
    USER_TO_BOND_ASSIGNMENT_KEY("corda:bt:user:assign:bonds#%s"), // list of bonds assigned to a user
    BOND_TO_USER_ASSIGNMENT_KEY("corda:bt:bond:assign:users#%s"), // list of users on a bond
    BOND_DETAILS_KEY("corda:bt:bond:details#%s"), // details of the bonds issued to user

    BOND_TERM_KEY("corda:bt#%s"), // bond terms

    LIST_BOND_KEYS("corda:bt:bond:keys"), // used to join the bondId to other keys

    USERS_KEY("corda:users#%s"), // users

    USER_ROLES_ASSIGNMENT_KEY("corda:users:roles#%s"); // list of roles assgined to user
    private String pattern;

    RedisKeys(String pattern) {
        this.pattern = pattern;
    }

    public String getPattern() {
        return pattern;
    }
}
