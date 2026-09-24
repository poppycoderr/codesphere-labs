package labs.ddd.layered.domain;

/** 场次聚合内的参会人条目。 */
public record Attendee(
        String attendeeId,
        Phone phone) {
}
