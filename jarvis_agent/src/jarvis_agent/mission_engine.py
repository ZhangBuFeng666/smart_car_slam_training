from typing import Any

from jarvis_agent.models import Action, MissionState, MissionView
from jarvis_agent.repository import Repository


class MissionNotFoundError(KeyError):
    pass


class MissionEngine:
    def __init__(self, repository: Repository, control: Any) -> None:
        self.repository = repository
        self.control = control

    async def confirm(self, mission_id: str) -> MissionView:
        mission = self._get_mission(mission_id)
        if mission.state is not MissionState.WAITING_CONFIRMATION:
            return mission

        self.repository.set_mission_state(mission_id, MissionState.RUNNING)
        try:
            for step in mission.plan.steps:
                await self._execute_step(mission_id, step.action, step.arguments)
            self.repository.append_timeline(mission_id, "running", "Mission is running")
            return self._get_mission(mission_id)
        except Exception as exc:
            self.repository.append_timeline(
                mission_id,
                "failure",
                "Mission paused after control failure",
                {"error": exc.__class__.__name__},
            )
            return self.repository.set_mission_state(mission_id, MissionState.PAUSED)

    async def cancel(self, mission_id: str) -> MissionView:
        self._get_mission(mission_id)
        await self.control.stop_all()
        self.repository.append_timeline(mission_id, "cancelled", "Mission cancelled")
        return self.repository.set_mission_state(mission_id, MissionState.CANCELLED)

    async def _execute_step(
        self, mission_id: str, action: Action, arguments: dict
    ) -> None:
        if action is Action.CHECK_STATUS:
            await self.control.status()
            self.repository.append_timeline(
                mission_id, "control", "Control service status checked"
            )
        elif action is Action.START_TASK:
            task = arguments["task"]
            await self.control.start_task(task)
            self.repository.append_timeline(
                mission_id, "control", "Started task: %s" % task
            )
        elif action is Action.STOP_TASK:
            task = arguments["task"]
            await self.control.stop_task(task)
            self.repository.append_timeline(
                mission_id, "control", "Stopped task: %s" % task
            )
        elif action is Action.STOP_ALL:
            await self.control.stop_all()
            self.repository.append_timeline(
                mission_id, "control", "Stopped all control tasks"
            )
        elif action is Action.RECORD_EVENT:
            self.repository.append_timeline(
                mission_id,
                "event",
                "Recorded event",
                {"arguments": dict(arguments)},
            )
        elif action is Action.ASK_USER:
            self.repository.append_timeline(
                mission_id,
                "question",
                arguments.get("question", "User decision required"),
            )
        elif action is Action.GENERATE_REPORT:
            self.repository.append_timeline(
                mission_id,
                "report_deferred",
                "Report generation deferred until finish decision",
            )
        elif action is Action.SET_INITIAL_POSE:
            await self.control.set_initial_pose(
                float(arguments["x"]),
                float(arguments["y"]),
                float(arguments["yaw"]),
            )
            self.repository.append_timeline(
                mission_id,
                "control",
                "Published initial pose (%.2f, %.2f, %.2f)"
                % (arguments["x"], arguments["y"], arguments["yaw"]),
            )
        elif action is Action.SET_NAV_GOAL:
            await self.control.set_nav_goal(
                float(arguments["x"]),
                float(arguments["y"]),
                float(arguments["yaw"]),
            )
            self.repository.append_timeline(
                mission_id,
                "control",
                "Published navigation goal (%.2f, %.2f, %.2f)"
                % (arguments["x"], arguments["y"], arguments["yaw"]),
            )

    def _get_mission(self, mission_id: str) -> MissionView:
        mission = self.repository.get_mission(mission_id)
        if mission is None:
            raise MissionNotFoundError("mission not found: %s" % mission_id)
        return mission
