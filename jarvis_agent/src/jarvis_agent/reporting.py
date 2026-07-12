from typing import List

from jarvis_agent.repository import Repository


class ReportService:
    def __init__(self, repository: Repository) -> None:
        self.repository = repository

    def build_fallback_report(self, mission_id: str) -> str:
        mission = self.repository.get_mission(mission_id)
        if mission is None:
            raise KeyError("mission not found: %s" % mission_id)

        lines: List[str] = [
            "# Jarvis Patrol Report",
            "",
            "Mission: `%s`" % mission.id,
            "State: `%s`" % mission.state.value,
            "",
            "## Plan",
            "",
            mission.plan.summary,
            "",
            "## Timeline",
            "",
        ]

        timeline = self.repository.list_timeline(mission_id)
        if not timeline:
            lines.append("- No timeline entries recorded.")
        for entry in timeline:
            lines.append(
                "- %s `%s`: %s"
                % (entry.timestamp.isoformat(), entry.kind, entry.message)
            )
            if entry.metadata.get("image_path"):
                lines.append("  Evidence: `%s`" % entry.metadata["image_path"])

        lines.extend(["", "## User Decisions", ""])
        decisions = self.repository.list_decisions(mission_id)
        if not decisions:
            lines.append("- No user decisions recorded.")
        for decision in decisions:
            lines.append(
                "- User decision: %s"
                % decision.decision.value
            )
            if decision.note:
                lines.append("  Note: %s" % decision.note)

        markdown = "\n".join(lines).rstrip() + "\n"
        self.repository.save_report(mission_id, markdown)
        return markdown
