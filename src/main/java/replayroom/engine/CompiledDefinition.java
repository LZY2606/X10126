package replayroom.engine;

import java.util.ArrayList;
import java.util.List;
import replayroom.model.Action;
import replayroom.model.Definition;
import replayroom.model.Transition;

/** A definition with all expressions parsed up front. */
public final class CompiledDefinition {

    public record CompiledAction(Action action, Expr target, Expr value, Expr message, Expr data) {}

    public record CompiledTransition(Transition transition, Expr condition, List<CompiledAction> actions) {}

    private final Definition definition;
    private final List<CompiledTransition> transitions;

    private CompiledDefinition(Definition definition, List<CompiledTransition> transitions) {
        this.definition = definition;
        this.transitions = List.copyOf(transitions);
    }

    public static CompiledDefinition compile(Definition definition) {
        List<CompiledTransition> compiled = new ArrayList<>();
        int transitionIndex = 0;
        for (Transition transition : definition.transitions()) {
            Expr condition = null;
            if (transition.condition() != null && !transition.condition().isBlank()) {
                try {
                    condition = Expr.compile(transition.condition());
                } catch (Expr.EvalException e) {
                    throw new IllegalArgumentException(
                            "transitions[" + transitionIndex + "].condition: " + e.getMessage());
                }
            }
            List<CompiledAction> actions = new ArrayList<>();
            int actionIndex = 0;
            for (Action action : transition.actions()) {
                Expr target = null;
                Expr value = null;
                Expr message = null;
                Expr data = null;
                try {
                    if ("set".equals(action.type())) {
                        target = Expr.compile(action.target());
                        value = Expr.compile(action.valueExpr());
                    } else if ("fail".equals(action.type())) {
                        message = Expr.compile(action.messageExpr());
                    } else if ("output".equals(action.type()) || "emit".equals(action.type())) {
                        if (action.dataExpr() != null) data = Expr.compile(action.dataExpr());
                    }
                } catch (Expr.EvalException e) {
                    throw new IllegalArgumentException(
                            "transitions[" + transitionIndex + "].actions[" + actionIndex + "]: " + e.getMessage());
                }
                actions.add(new CompiledAction(action, target, value, message, data));
                actionIndex++;
            }
            compiled.add(new CompiledTransition(transition, condition, actions));
            transitionIndex++;
        }
        return new CompiledDefinition(definition, compiled);
    }

    public Definition definition() { return definition; }
    public List<CompiledTransition> transitions() { return transitions; }
    public String fingerprint() { return definition.fingerprint(); }
}
