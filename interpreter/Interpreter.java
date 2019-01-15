package com.interpreter;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

import static com.interpreter.TokenType.*;

class Interpreter implements Expr.Visitor<Object>, Stmt.Visitor<Void> {
	// These exceptions are used to jump the call stack to the loop
	// where break and continue statements can be handled.
	private static class BreakException extends RuntimeException {}
	private static class ContinueException extends RuntimeException {}

	Environment globals = new Environment();
	private final Map<Expr, Integer> locals = new HashMap<>();
	private Environment environment = globals;

	public void interpret(List<Stmt> statements) {
		try {
			for (Stmt statement : statements) {
				execute(statement);
			}
		} catch(RuntimeError error) {
			QED.runtimeError(error);
		}
	}

	private void execute(Stmt stmt) {
		stmt.accept(this);
	}

	void resolve(Expr expr, int depth) {
		if (depth != -1)
			locals.put(expr, depth);
		// else it's a global variable
	}

	private Object evaluate(Expr expr) {
		return expr.accept(this);
	}

	@Override
	public Void visitBlockStmt(Stmt.Block stmt) {
		executeBlock(stmt.statements, new Environment(environment));
		return null;
	}

	public void executeBlock(List<Stmt> block, Environment env) {
		Environment previous = environment;
		environment = env;

		// Even if a runtimeError is thrown we ensure that the environment
		// gets restored. This is mainly important in a REPL session
		try {
			for (Stmt statement : block) {
				execute(statement);
			}
		} finally {
			environment = previous;
		}
	}

	@Override
	public Void visitClassStmt(Stmt.Class stmt) {
		environment.define(stmt.name.lexeme, null);
		Class klass = new Class(stmt.name.lexeme);
		environment.assign(stmt.name, klass);
		return null;
	}

	@Override
	public Void visitFunctionStmt(Stmt.Function stmt) {
		environment.define(stmt.name.lexeme, new Function(stmt, environment));
		return null;
	}

	@Override
	public Void visitIfStmt(Stmt.If stmt) {
		if (isTruthy(evaluate(stmt.condition)))
			execute(stmt.thenBranch);
		else if (stmt.elseBranch != null)
			execute(stmt.elseBranch);
		return null;
	}

	@Override
	public Void visitWhileStmt(Stmt.While stmt) {
		try {
			while(isTruthy(evaluate(stmt.condition))) {
				try {
					execute(stmt.body);
				} catch (ContinueException ex) {
					// Do nothing
				}
			}
		} catch (BreakException ex) {
			// Do nothing
		}
		return null;
	}

	@Override
	public Void visitForStmt(Stmt.For stmt) {
		if (stmt.initializer != null) {
			execute(stmt.initializer);
		}

		try {
			while(isTruthy(evaluate(stmt.condition))) {
				try {
					execute(stmt.body);
				} catch (ContinueException ex) {
					// Do nothing
				}
				if (stmt.increment != null)
					evaluate(stmt.increment);
			}
		} catch (BreakException ex) {
			// Do nothing
		}

		return null;
	}

	@Override
	public Void visitBreakStmt(Stmt.Break stmt) {
		throw new BreakException();
	}

	@Override
	public Void visitContinueStmt(Stmt.Continue stmt) {
		throw new ContinueException();
	}

	@Override
	public Void visitReturnStmt(Stmt.Return stmt) {
		Object value = stmt.value == null ? null : evaluate(stmt.value);
		throw new ReturnException(value);
	}

	@Override
	public Void visitPrintStmt(Stmt.Print stmt) {
		for (Expr expr : stmt.expressions) {
			Object val = evaluate(expr);
			System.out.print(stringify(val) + " ");
		}
		System.out.println();
		return null;
	}

	@Override
	public Void visitExpressionStmt(Stmt.Expression stmt) {
		evaluate(stmt.expression);
		return null;
	}

	@Override
	public Void visitVarStmt(Stmt.Var stmt) {
		Object value = null;
		if (stmt.initializer != null)
			value = evaluate(stmt.initializer);

		environment.define(stmt.name.lexeme, value);
		return null;
	}

	@Override
	public Object visitAssignExpr(Expr.Assign expr) {
		Object value = evaluate(expr.value);

		Integer distance = locals.get(expr);
		if (distance != null) {
			environment.assignAt(distance, expr.name, value);
		} else {
			globals.assign(expr.name, value);
		}

		return value;
	}

	@Override
	public Object visitSetExpr(Expr.Set expr) {
		Object object = evaluate(expr.object);
		if (!(object instanceof Instance)) {
			throw new RuntimeError(expr.name, "Can't set a field in non instance object");
		}

		Object value = evaluate(expr.value);
		((Instance)object).set(expr.name, value);
		return value;
	}

	@Override
	public Object visitBinaryExpr(Expr.Binary expr) {
		Object left = evaluate(expr.left);
		Object right = evaluate(expr.right);

		switch (expr.operator.type) {
			case PLUS:
				if (left instanceof Double && right instanceof Double)
					return (double)left + (double)right;
				if (left instanceof String && right instanceof String)
					return (String)left + (String)right;
				throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");
			case MINUS:
				checkNumberOperands(expr.operator, left, right);
				return (double)left - (double)right;
			case STAR:
				checkNumberOperands(expr.operator, left, right);
				return (double)left * (double)right;
			case SLASH:
				checkNumberOperands(expr.operator, left, right);
				if ((double)right == 0)
					throw new RuntimeError(expr.operator, "Division by zero");
				return (double)left / (double)right;
			case GREATER:
				if (left instanceof Double && right instanceof Double)
					return (double)left > (double)right;
				if (left instanceof String && right instanceof String)
					return ((String)left).compareTo((String)right) > 0;
				throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");
			case GREATER_EQUAL:
				if (left instanceof Double && right instanceof Double)
					return (double)left >= (double)right;
				if (left instanceof String && right instanceof String)
					return ((String)left).compareTo((String)right) >= 0;
				throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");
			case LESS:
				if (left instanceof Double && right instanceof Double)
					return (double)left < (double)right;
				if (left instanceof String && right instanceof String)
					return ((String)left).compareTo((String)right) < 0;
				throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");
			case LESS_EQUAL:
				if (left instanceof Double && right instanceof Double)
					return (double)left <= (double)right;
				if (left instanceof String && right instanceof String)
					return ((String)left).compareTo((String)right) <= 0;
				throw new RuntimeError(expr.operator, "Operands must be two numbers or two strings");

			case EQUAL_EQUAL:
				return isEqual(left, right);
			case BANG_EQUAL:
				return !isEqual(left, right);

			case COMMA:
				return right;
		}

		// unreachable
		return null;
	}

	@Override
	public Object visitCallExpr(Expr.Call expr) {
		Object callee = evaluate(expr.callee);
		
		List<Object> args = new ArrayList<>();
		for (Expr arg : expr.arguments) {
			args.add(evaluate(arg));
		}

		if (!(callee instanceof Callable)) {
			throw new RuntimeError(expr.paren, "Object isn't callable");
		}

		Callable function = (Callable)callee;
		if (args.size() != function.arity()) {
			throw new RuntimeError(expr.paren,
				"Expected " + function.arity() + " arguments "
				+ "but got " + args.size() + " instead."
			);
		}

		return function.call(this, args);
	}

	@Override
	public Object visitGetExpr(Expr.Get expr) {
		Object instance = evaluate(expr.object);
		if (instance instanceof Instance) {
			return ((Instance) instance).get(expr.name);
		} else {
			throw new RuntimeError(expr.name, "Only instances have properties");
		}
	}

	@Override
	public Object visitLogicalExpr(Expr.Logical expr) {
		Object left = evaluate(expr.left);
		switch (expr.operator.type) {
			case OR:
				if (isTruthy(left)) return left; break;
			case AND:
				if (!isTruthy(left)) return left; break;
		}

		return evaluate(expr.right);
	}

	@Override
	public Object visitGroupingExpr(Expr.Grouping expr) {
		return evaluate(expr.expression);
	}

	@Override
	public Object visitLiteralExpr(Expr.Literal expr) {
		return expr.value;
	}

	@Override
	public Object visitUnaryExpr(Expr.Unary expr) {
		Object right = evaluate(expr.right);
		if (expr.operator.type == MINUS) {
			checkNumberOperand(expr.operator, right);
			return -(double)(right);
		}
		if (expr.operator.type == BANG)
			return !isTruthy(right);

		// unreachable
		return null;
	}

	@Override
	public Object visitTernaryExpr(Expr.Ternary expr) {
		if (isTruthy(evaluate(expr.condition)))
			return evaluate(expr.onTrue);

		return evaluate(expr.onFalse);
	}

	@Override
	public Object visitVariableExpr(Expr.Variable expr) {
		return lookUpVariable(expr.name, expr);
	}

	private Object lookUpVariable(Token name, Expr expr) {
		Integer distance = locals.get(expr);
		if (distance != null) {
			return environment.getAt(distance, name);
		} else {
			return globals.get(name);
		}
	}

	private boolean isTruthy(Object test) {
		if (test == null) return false;
		if (test instanceof Boolean) return (boolean)test;

		return true;
	}

	private boolean isEqual(Object a, Object b) {
		if (a == null && b == null) return true;
		// If they aren't both null ^ but one of them is
		// then they aren't equal
		if (a == null || b == null) return false;

		return a.equals(b);
	}

	private String stringify(Object object) {
		if (object == null) return "nil";

		// Work around Java adding ".0" to integer-valued doubles
		if (object instanceof Double) {
			String text = object.toString();
			if (text.endsWith(".0"))
				text = text.substring(0, text.length() - 2);
			return text;
		}

		return object.toString();
	}

	private void checkNumberOperand(Token operator, Object operand) {
		if (operand instanceof Double) return;
		throw new RuntimeError(operator, "Operand must be a number");
	}

	private void checkNumberOperands(Token operator, Object left, Object right) {
		if (left instanceof Double && right instanceof Double) return;
		throw new RuntimeError(operator, "Operands must be numbers");
	}
}