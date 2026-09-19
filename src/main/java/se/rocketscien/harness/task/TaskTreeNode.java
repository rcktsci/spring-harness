package se.rocketscien.harness.task;

import java.util.List;

/**
 * Узел дерева подзадач ({@code GET /tasks/{id}/tree}): задача + дети (порядок —
 * по {@code createdAt, id}).
 */
public record TaskTreeNode(Task task, List<TaskTreeNode> children) {
}
