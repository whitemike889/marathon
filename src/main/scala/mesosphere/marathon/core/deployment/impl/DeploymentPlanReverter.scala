package mesosphere.marathon
package core.deployment.impl

import com.typesafe.scalalogging.StrictLogging
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.state._

/**
  * Contains the logic to revert deployments by calculating the changes
  * of the deployment and applying the reverse on the given group.
  */
private[deployment] object DeploymentPlanReverter extends StrictLogging {

  /**
    * Reverts this plan by applying the reverse changes to the given Group.
    *
    * If they were no concurrent deployments and the passed group is equal to
    * the target group, this method should result in the original group
    * except for version/timestamp changes.
    *
    * If there were concurrent changes, this method tries to revert the changes
    * of this deployment only without affecting the other deployments.
    */
  def revert(original: RootGroup, target: RootGroup, newVersion: Timestamp = Group.defaultVersion): RootGroup => RootGroup = {

    def changesOnIds[T](originalById: Map[AbsolutePathId, T], targetById: Map[AbsolutePathId, T]): Seq[(Option[T], Option[T])] = {
      val ids = originalById.keys ++ targetById.keys
      ids.iterator.map { id => originalById.get(id) -> targetById.get(id) }.toSeq
    }

    /* a sequence of tuples with the old and the new group definition (also for unchanged groups) */
    val groupChanges: Seq[(Option[Group], Option[Group])] =
      changesOnIds(original.transitiveGroupsById, target.transitiveGroupsById)

    /* a sequence of tuples with the old and the new run definition */
    val runSpecChanges: Seq[(Option[RunSpec], Option[RunSpec])] = {
      val ids = original.transitiveRunSpecIds ++ target.transitiveRunSpecIds
      ids.map { id => original.runSpec(id) -> target.runSpec(id) }
        .filter { case (oldOpt, newOpt) => oldOpt != newOpt }
        .toIndexedSeq
    }

    // We need to revert run changes first so that runs have already been deleted when we check
    // a group is empty and can be removed.
    (revertRunSpecChanges(newVersion, runSpecChanges) _).andThen(revertGroupChanges(newVersion, groupChanges))
  }

  /**
    * Reverts any group additions, changes, removals.
    *
    * It is more difficult than reverting any app definition changes
    * because groups are not locked by deployments and concurrent changes are allowed.
    */
  private[this] def revertGroupChanges(
    version: Timestamp,
    groupChanges: Seq[(Option[Group], Option[Group])])(rootGroup: RootGroup): RootGroup = {

    def revertGroupRemoval(oldGroup: Group)(dependencies: Set[AbsolutePathId]): Set[AbsolutePathId] = {
      logger.debug("re-adding group {} with dependencies {}", Seq(oldGroup.id, oldGroup.dependencies): _*)
      if ((oldGroup.dependencies -- dependencies).nonEmpty) dependencies ++ oldGroup.dependencies else dependencies
    }

    def revertDependencyChanges(oldGroup: Group, newGroup: Group)(dependencies: Set[AbsolutePathId]): Set[AbsolutePathId] = {
      val removedDependencies = oldGroup.dependencies -- newGroup.dependencies
      val addedDependencies = newGroup.dependencies -- oldGroup.dependencies

      if (removedDependencies.nonEmpty || addedDependencies.nonEmpty) {
        logger.debug(
          s"revert dependency changes in group ${oldGroup.id}, " +
            s"readding removed {${removedDependencies.mkString(", ")}}, " +
            s"removing added {${addedDependencies.mkString(", ")}}")

        dependencies ++ removedDependencies -- addedDependencies
      } else {
        // common case, unchanged
        dependencies
      }
    }

    def revertGroupAddition(result: RootGroup, newGroup: Group): RootGroup = {
      // We know that all group removals for groups inside of this group have been
      // performed before. If there are no conflicts with other deployments, we could
      // just remove the group. Unfortunately, another deployment could have
      // added a new sub group or sub app definition. In this case, we should not remove
      // this group.

      def isGroupUnchanged(group: Group): Boolean =
        !group.containsAppsOrPodsOrGroups && group.id == newGroup.id && group.dependencies == newGroup.dependencies

      result.group(newGroup.id) match {
        case Some(unchanged) if isGroupUnchanged(unchanged) =>
          logger.debug("remove unchanged group {}", unchanged.id)
          result.removeGroup(unchanged.id, version)
        case _ if newGroup.dependencies.nonEmpty =>
          // group dependencies have changed
          logger.debug(s"group ${newGroup.id} has changed. " +
            s"Removed added dependencies ${newGroup.dependencies.mkString(", ")}")
          result.updateDependencies(newGroup.id, _ -- newGroup.dependencies, version = version)
        case _ =>
          // still contains apps/groups, so we keep it
          result
      }
    }

    // sort groups so that inner groups are processed first
    val sortedGroupChanges = groupChanges.sortWith {
      case (change1, change2) =>
        // both groups are supposed to have the same path id (if there are any)
        def pathId(change: (Option[Group], Option[Group])): PathId = {
          Seq(change._1, change._2).flatten.headOption.fold(PathId.root: PathId)(_.id)
        }

        pathId(change1) > pathId(change2)
    }

    sortedGroupChanges.foldLeft(rootGroup) {
      case (result, groupUpdate) =>
        groupUpdate match {
          case (Some(oldGroup), None) =>
            result.updateDependencies(oldGroup.id, revertGroupRemoval(oldGroup), version = version)
          case (Some(oldGroup), Some(newGroup)) =>
            result.updateDependencies(oldGroup.id, revertDependencyChanges(oldGroup, newGroup), version = version)

          case (None, Some(newGroup)) =>
            revertGroupAddition(result, newGroup)

          case (None, None) =>
            logger.warn("processing unexpected NOOP in group changes")
            result
        }
    }
  }

  /**
    * Reverts app and pod additions, changes and removals.
    *
    * The logic is quite simple because during a deployment apps are locked which
    * prevents any concurrent changes.
    */
  private[this] def revertRunSpecChanges(
    version: Timestamp, changes: Seq[(Option[RunSpec], Option[RunSpec])])(
    rootGroup: RootGroup): RootGroup = {

    def appOrPodChange(
      runnableSpec: RunSpec,
      appChange: AppDefinition => RootGroup,
      podChange: PodDefinition => RootGroup): RootGroup = runnableSpec match {
      case app: AppDefinition => appChange(app)
      case pod: PodDefinition => podChange(pod)
    }

    changes.foldLeft(rootGroup) {
      case (result, runUpdate) =>
        runUpdate match {
          case (Some(oldRun), _) => //removal or change
            logger.debug("revert to old app definition {}", oldRun.id)
            appOrPodChange(
              oldRun,
              app => result.updateApp(app.id, _ => app, version),
              pod => result.updatePod(pod.id, _ => pod, version))
          case (None, Some(newRun)) =>
            logger.debug("remove app definition {}", newRun.id)
            appOrPodChange(
              newRun,
              app => result.removeApp(app.id, version),
              pod => result.removePod(pod.id, version))
          case (None, None) =>
            logger.warn("processing unexpected NOOP in app changes")
            result
        }
    }
  }
}
