package org.debian.decopy.datatypes;

/**
 * A copyright holder with name, email, and year range.
 */
public final class CopyrightHolder {

    private String name;
    private String email;
    private YearRange years;

    public CopyrightHolder(String name, String email, YearRange years) {
        this.name = name != null ? name : "";
        this.email = email != null ? email : "";
        this.years = years != null ? years : new YearRange();
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public YearRange getYears() { return years; }

    public String getPerson() {
        if (!name.isEmpty() && !email.isEmpty()) {
            return name + " <" + email + ">";
        } else if (!email.isEmpty()) {
            return "<" + email + ">";
        }
        return name;
    }

    public CopyrightHolder merge(CopyrightHolder other) {
        if (!other.name.isEmpty() && years.newer(other.years)) {
            this.name = other.name;
        }
        years.merge(other.years);
        return this;
    }

    @Override
    public String toString() {
        String yearStr = years.toString();
        if (!yearStr.isEmpty()) {
            return yearStr + ", " + getPerson();
        }
        return getPerson();
    }

}
