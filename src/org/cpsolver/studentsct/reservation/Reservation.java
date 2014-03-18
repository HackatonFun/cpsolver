package org.cpsolver.studentsct.reservation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.cpsolver.ifs.assignment.Assignment;
import org.cpsolver.ifs.assignment.AssignmentComparable;
import org.cpsolver.ifs.assignment.context.AbstractClassWithContext;
import org.cpsolver.ifs.assignment.context.AssignmentConstraintContext;
import org.cpsolver.ifs.model.Model;
import org.cpsolver.studentsct.model.Config;
import org.cpsolver.studentsct.model.Course;
import org.cpsolver.studentsct.model.CourseRequest;
import org.cpsolver.studentsct.model.Enrollment;
import org.cpsolver.studentsct.model.Offering;
import org.cpsolver.studentsct.model.Request;
import org.cpsolver.studentsct.model.Section;
import org.cpsolver.studentsct.model.Student;
import org.cpsolver.studentsct.model.Subpart;



/**
 * Abstract reservation. This abstract class allow some section, courses,
 * and other parts to be reserved to particular group of students. A reservation
 * can be unlimited (any number of students of that particular group can attend
 * a course, section, etc.) or with a limit (only given number of seats is
 * reserved to the students of the particular group).
 * 
 * <br>
 * <br>
 * 
 * @version StudentSct 1.3 (Student Sectioning)<br>
 *          Copyright (C) 2007 - 2014 Tomas Muller<br>
 *          <a href="mailto:muller@unitime.org">muller@unitime.org</a><br>
 *          <a href="http://muller.unitime.org">http://muller.unitime.org</a><br>
 * <br>
 *          This library is free software; you can redistribute it and/or modify
 *          it under the terms of the GNU Lesser General Public License as
 *          published by the Free Software Foundation; either version 3 of the
 *          License, or (at your option) any later version. <br>
 * <br>
 *          This library is distributed in the hope that it will be useful, but
 *          WITHOUT ANY WARRANTY; without even the implied warranty of
 *          MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *          Lesser General Public License for more details. <br>
 * <br>
 *          You should have received a copy of the GNU Lesser General Public
 *          License along with this library; if not see
 *          <a href='http://www.gnu.org/licenses/'>http://www.gnu.org/licenses/</a>.
 */
public abstract class Reservation extends AbstractClassWithContext<Request, Enrollment, Reservation.ReservationContext> implements AssignmentComparable<Reservation, Request, Enrollment> {
    /** Reservation unique id */
    private long iId = 0;
    
    /** Is reservation expired? */
    private boolean iExpired;
    
    /** Instructional offering on which the reservation is set, required */
    private Offering iOffering;

    /** One or more configurations, if applicable */ 
    private Set<Config> iConfigs = new HashSet<Config>();
    
    /** One or more sections, if applicable */
    private Map<Subpart, Set<Section>> iSections = new HashMap<Subpart, Set<Section>>();
    
    /**
     * Constructor
     * @param id reservation unique id
     * @param offering instructional offering on which the reservation is set
     */
    public Reservation(long id, Offering offering) {
        iId = id;
        iOffering = offering;
        iOffering.getReservations().add(this);
        iOffering.clearReservationCache();
    }
    
    /**
     * Reservation  id
     */
    public long getId() { return iId; }
    
    /**
     * Reservation limit
     */
    public abstract double getReservationLimit();
    
    
    /** Reservation priority (e.g., individual reservations first) */
    public abstract int getPriority();
    
    /**
     * Returns true if the student is applicable for the reservation
     * @param student a student 
     * @return true if student can use the reservation to get into the course / configuration / section
     */
    public abstract boolean isApplicable(Student student);

    /**
     * Instructional offering on which the reservation is set.
     */
    public Offering getOffering() { return iOffering; }
    
    /**
     * One or more configurations on which the reservation is set (optional).
     */
    public Set<Config> getConfigs() { return iConfigs; }
    
    /**
     * Add a configuration (of the offering {@link Reservation#getOffering()}) to this reservation
     */
    public void addConfig(Config config) {
        iConfigs.add(config);
        clearLimitCapCache();
    }
    
    /**
     * One or more sections on which the reservation is set (optional).
     */
    public Map<Subpart, Set<Section>> getSections() { return iSections; }
    
    /**
     * One or more sections on which the reservation is set (optional).
     */
    public Set<Section> getSections(Subpart subpart) {
        return iSections.get(subpart);
    }
    
    /**
     * Add a section (of the offering {@link Reservation#getOffering()}) to this reservation.
     * This will also add all parent sections and the appropriate configuration to the offering.
     */
    public void addSection(Section section) {
        addConfig(section.getSubpart().getConfig());
        while (section != null) {
            Set<Section> sections = iSections.get(section.getSubpart());
            if (sections == null) {
                sections = new HashSet<Section>();
                iSections.put(section.getSubpart(), sections);
            }
            sections.add(section);
            section = section.getParent();
        }
        clearLimitCapCache();
    }
    
    /**
     * Return true if the given enrollment meets the reservation.
     */
    public boolean isIncluded(Enrollment enrollment) {
        // Free time request are never included
        if (enrollment.getConfig() == null) return false;
        
        // Check the offering
        if (!iOffering.equals(enrollment.getConfig().getOffering())) return false;
        
        // If there are configurations, check the configuration
        if (!iConfigs.isEmpty() && !iConfigs.contains(enrollment.getConfig())) return false;
        
        // Check all the sections of the enrollment
        for (Section section: enrollment.getSections()) {
            Set<Section> sections = iSections.get(section.getSubpart());
            if (sections != null && !sections.contains(section))
                return false;
        }
        
        return true;
    }
    
    /**
     * True if the enrollment can be done using this configuration
     */
    public boolean canEnroll(Assignment<Request, Enrollment> assignment, Enrollment enrollment) {
        // Check if student can use this reservation
        if (!isApplicable(enrollment.getStudent())) return false;
        
        // Check if the enrollment meets the reservation
        if (!isIncluded(enrollment)) return false;

        // Check the limit
        return getLimit() < 0 || getContext(assignment).getUsedSpace() + enrollment.getRequest().getWeight() <= getLimit();
    }
    
    /**
     * True if can go over the course / config / section limit. Only to be used in the online sectioning. 
      */
    public abstract boolean canAssignOverLimit();
    
    /**
     * If true, student must use the reservation (if applicable)
     */
    public abstract boolean mustBeUsed();
    
    /**
     * Reservation restrictivity (estimated percentage of enrollments that include this reservation, 1.0 reservation on the whole offering)
     */
    public double getRestrictivity() {
        if (iCachedRestrictivity == null) {
            if (getConfigs().isEmpty()) return 1.0;
            int nrChoices = 0, nrMatchingChoices = 0;
            for (Config config: getOffering().getConfigs()) {
                int x[] = nrChoices(config, 0, new HashSet<Section>(), getConfigs().contains(config));
                nrChoices += x[0];
                nrMatchingChoices += x[1];
            }
            iCachedRestrictivity = ((double)nrMatchingChoices) / nrChoices;
        }
        return iCachedRestrictivity;
    }
    private Double iCachedRestrictivity = null;
    
    
    /** Number of choices and number of chaing choices in the given sub enrollment */
    private int[] nrChoices(Config config, int idx, HashSet<Section> sections, boolean matching) {
        if (config.getSubparts().size() == idx) {
            return new int[]{1, matching ? 1 : 0};
        } else {
            Subpart subpart = config.getSubparts().get(idx);
            Set<Section> matchingSections = getSections(subpart);
            int choicesThisSubpart = 0;
            int matchingChoicesThisSubpart = 0;
            for (Section section : subpart.getSections()) {
                if (section.getParent() != null && !sections.contains(section.getParent()))
                    continue;
                if (section.isOverlapping(sections))
                    continue;
                sections.add(section);
                boolean m = matching && (matchingSections == null || matchingSections.contains(section));
                int[] x = nrChoices(config, 1 + idx, sections, m);
                choicesThisSubpart += x[0];
                matchingChoicesThisSubpart += x[1];
                sections.remove(section);
            }
            return new int[] {choicesThisSubpart, matchingChoicesThisSubpart};
        }
    }
    
    /**
     * Priority first, than restrictivity (more restrictive first), than availability (more available first), than id 
     */
    @Override
    public int compareTo(Assignment<Request, Enrollment> assignment, Reservation r) {
        if (getPriority() != r.getPriority()) {
            return (getPriority() < r.getPriority() ? -1 : 1);
        }
        int cmp = Double.compare(getRestrictivity(), r.getRestrictivity());
        if (cmp != 0) return cmp;
        cmp = - Double.compare(getContext(assignment).getReservedAvailableSpace(assignment, null), r.getContext(assignment).getReservedAvailableSpace(assignment, null));
        if (cmp != 0) return cmp;
        return new Long(getId()).compareTo(r.getId());
    }
    
    /**
     * Return minimum of two limits where -1 counts as unlimited (any limit is smaller)
     */
    private static double min(double l1, double l2) {
        return (l1 < 0 ? l2 : l2 < 0 ? l1 : Math.min(l1, l2));
    }
    
    /**
     * Add two limits where -1 counts as unlimited (unlimited plus anything is unlimited)
     */
    private static double add(double l1, double l2) {
        return (l1 < 0 ? -1 : l2 < 0 ? -1 : l1 + l2);
    }
    

    /** Limit cap cache */
    private Double iLimitCap = null;

    /**
     * Compute limit cap (maximum number of students that can get into the offering using this reservation)
     */
    public double getLimitCap() {
        if (iLimitCap == null) iLimitCap = getLimitCapNoCache();
        return iLimitCap;
    }

    /**
     * Compute limit cap (maximum number of students that can get into the offering using this reservation)
     */
    private double getLimitCapNoCache() {
        if (getConfigs().isEmpty()) return -1; // no config -> can be unlimited
        
        if (canAssignOverLimit()) return -1; // can assign over limit -> no cap
        
        // config cap
        double cap = 0;
        for (Config config: iConfigs)
            cap = add(cap, config.getLimit());
        
        for (Set<Section> sections: getSections().values()) {
            // subpart cap
            double subpartCap = 0;
            for (Section section: sections)
                subpartCap = add(subpartCap, section.getLimit());
            
            // minimize
            cap = min(cap, subpartCap);
        }
        
        return cap;
    }
    
    /**
     * Clear limit cap cache
     */
    private void clearLimitCapCache() {
        iLimitCap = null;
    }
    
    /**
     * Reservation limit capped the limit cap (see {@link Reservation#getLimitCap()})
     */
    public double getLimit() {
        return min(getLimitCap(), getReservationLimit());
    }
    
    /**
     * True if holding this reservation allows a student to have attend overlapping class. 
     */
    public boolean isAllowOverlap() {
        return false;
    }
    
    /**
     * Set reservation expiration. If a reservation is expired, it works as ordinary reservation
     * (especially the flags mutBeUsed and isAllowOverlap), except it does not block other students
     * of getting into the offering / config / section.  
     */
    public void setExpired(boolean expired) {
        iExpired = expired;
    }
    
    /**
     * True if the reservation is expired. If a reservation is expired, it works as ordinary reservation
     * (especially the flags mutBeUsed and isAllowOverlap), except it does not block other students
     * of getting into the offering / config / section.
     */
    public boolean isExpired() {
        return iExpired;
    }
    
    @Override
    public Model<Request, Enrollment> getModel() {
        return getOffering().getModel();
    }
    
    /**
     * Available reserved space
     * @param excludeRequest excluding given request (if not null)
     **/
    public double getReservedAvailableSpace(Assignment<Request, Enrollment> assignment, Request excludeRequest) {
        return getContext(assignment).getReservedAvailableSpace(assignment, excludeRequest);
    }
    
    /** Enrollments assigned using this reservation */
    public Set<Enrollment> getEnrollments(Assignment<Request, Enrollment> assignment) {
        return getContext(assignment).getEnrollments();
    }

    @Override
    public ReservationContext createAssignmentContext(Assignment<Request, Enrollment> assignment) {
        return new ReservationContext(assignment);
    }
    
    public class ReservationContext implements AssignmentConstraintContext<Request, Enrollment> {
        /** Enrollments included in this reservation */
        private Set<Enrollment> iEnrollments = new HashSet<Enrollment>();
        
        /** Used part of the limit */
        private double iUsed = 0;

        public ReservationContext(Assignment<Request, Enrollment> assignment) {
            for (Course course: getOffering().getCourses())
                for (CourseRequest request: course.getRequests()) {
                    Enrollment enrollment = assignment.getValue(request);
                    if (enrollment != null && Reservation.this.equals(enrollment.getReservation()))
                        assigned(assignment, enrollment);
                }
        }

        /** Notify reservation about an unassignment */
        @Override
        public void assigned(Assignment<Request, Enrollment> assignment, Enrollment enrollment) {
            iEnrollments.add(enrollment);
            iUsed += enrollment.getRequest().getWeight();
        }

        /** Notify reservation about an assignment */
        @Override
        public void unassigned(Assignment<Request, Enrollment> assignment, Enrollment enrollment) {
            iEnrollments.remove(enrollment);
            iUsed -= enrollment.getRequest().getWeight();
        }
        
        /** Enrollments assigned using this reservation */
        public Set<Enrollment> getEnrollments() {
            return iEnrollments;
        }
        
        /** Used space */
        public double getUsedSpace() {
            return iUsed;
        }
        
        /**
         * Available reserved space
         * @param excludeRequest excluding given request (if not null)
         **/
        public double getReservedAvailableSpace(Assignment<Request, Enrollment> assignment, Request excludeRequest) {
            // Unlimited
            if (getLimit() < 0) return Double.MAX_VALUE;
            
            double reserved = getLimit() - getContext(assignment).getUsedSpace();
            if (excludeRequest != null && assignment.getValue(excludeRequest) != null && iEnrollments.contains(assignment.getValue(excludeRequest)))
                reserved += excludeRequest.getWeight();
            
            return reserved;
        }
    }
}
