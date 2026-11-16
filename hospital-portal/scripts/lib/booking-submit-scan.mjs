import ts from 'typescript';

/** Enumerate booking calls through injected services, regardless of the field's name. */
export function bookingSubmitViolations(source, filename = 'component.ts') {
  const ast = ts.createSourceFile(filename, source, ts.ScriptTarget.Latest, true);
  const violations = [];
  let calls = 0;
  function inspectClass(node) {
    const services = new Map();
    for (const member of node.members) {
      if (!ts.isPropertyDeclaration(member) || !member.initializer) continue;
      const init = member.initializer;
      if (!ts.isCallExpression(init) || init.expression.getText(ast) !== 'inject') continue;
      const service = init.arguments[0]?.getText(ast);
      if (service === 'AppointmentService') services.set(member.name.getText(ast), 'create');
      if (service === 'PatientPortalService')
        services.set(member.name.getText(ast), 'bookAppointment');
    }
    for (const member of node.members) {
      if (!ts.isMethodDeclaration(member) || !member.body) continue;
      function visit(current) {
        if (ts.isCallExpression(current) && ts.isPropertyAccessExpression(current.expression)) {
          const method = current.expression;
          const receiver = method.expression;
          if (
            ts.isPropertyAccessExpression(receiver) &&
            receiver.expression.kind === ts.SyntaxKind.ThisKeyword &&
            services.get(receiver.name.text) === method.name.text
          ) {
            calls++;
            const body = member.body.getText(ast);
            const before = source.slice(member.body.pos, current.pos);
            const gate = member.body.statements.find(
              (statement) =>
                ts.isIfStatement(statement) &&
                statement.thenStatement.getText(ast).includes('return;') &&
                /this\.\w+\(\)/u.test(statement.expression.getText(ast)),
            );
            const guardedSignals = gate
              ? [...gate.expression.getText(ast).matchAll(/this\.(\w+)\(\)/gu)].map((hit) => hit[1])
              : [];
            if (
              !gate ||
              gate.end > current.pos ||
              !guardedSignals.some((signal) => before.includes(`this.${signal}.set(true)`)) ||
              !body.includes('bookingRequestId: this.bookingRequestId') ||
              body.includes('crypto.randomUUID(')
            ) {
              violations.push(
                `${filename}:${member.name.getText(ast)} must guard booking and retain its request ID`,
              );
            }
          }
        }
        ts.forEachChild(current, visit);
      }
      visit(member.body);
    }
  }
  function walk(node) {
    if (ts.isClassDeclaration(node)) inspectClass(node);
    ts.forEachChild(node, walk);
  }
  walk(ast);
  return { calls, violations };
}
