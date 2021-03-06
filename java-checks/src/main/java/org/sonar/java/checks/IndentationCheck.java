/*
 * SonarQube Java
 * Copyright (C) 2012-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.java.checks;

import com.google.common.collect.ImmutableList;
import org.sonar.api.server.rule.RulesDefinition;
import org.sonar.check.Priority;
import org.sonar.check.Rule;
import org.sonar.check.RuleProperty;
import org.sonar.java.syntaxtoken.FirstSyntaxTokenFinder;
import org.sonar.java.syntaxtoken.LastSyntaxTokenFinder;
import org.sonar.java.tag.Tag;
import org.sonar.plugins.java.api.JavaFileScannerContext;
import org.sonar.plugins.java.api.tree.BlockTree;
import org.sonar.plugins.java.api.tree.CaseGroupTree;
import org.sonar.plugins.java.api.tree.CaseLabelTree;
import org.sonar.plugins.java.api.tree.ClassTree;
import org.sonar.plugins.java.api.tree.SyntaxToken;
import org.sonar.plugins.java.api.tree.Tree;
import org.sonar.plugins.java.api.tree.Tree.Kind;
import org.sonar.squidbridge.annotations.SqaleConstantRemediation;
import org.sonar.squidbridge.annotations.SqaleSubCharacteristic;

import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

@Rule(
  key = "IndentationCheck",
  name = "Source code should be indented consistently",
  priority = Priority.MINOR,
  tags = {Tag.CONVENTION})
@SqaleSubCharacteristic(RulesDefinition.SubCharacteristics.READABILITY)
@SqaleConstantRemediation("1min")
public class IndentationCheck extends SubscriptionBaseVisitor {

  private static final List<Kind> BLOCK_TYPES = ImmutableList.of(
    Kind.CLASS,
    Kind.INTERFACE,
    Kind.ENUM,
    Kind.ANNOTATION_TYPE,
    Kind.CLASS,
    Kind.BLOCK,
    Kind.STATIC_INITIALIZER,
    Kind.INITIALIZER,
    Kind.SWITCH_STATEMENT,
    Kind.CASE_GROUP
  );

  private static final int DEFAULT_INDENTATION_LEVEL = 2;

  @RuleProperty(
    key = "indentationLevel",
    description = "Number of white-spaces of an indent. If this property is not set, we just check that the code is indented.",
    defaultValue = "" + DEFAULT_INDENTATION_LEVEL)
  public int indentationLevel = DEFAULT_INDENTATION_LEVEL;

  private int expectedLevel;
  private boolean isBlockAlreadyReported;
  private int lastCheckedLine;
  private Deque<Boolean> isInAnonymousClass = new LinkedList<>();

  @Override
  public List<Kind> nodesToVisit() {
    return BLOCK_TYPES;
  }

  @Override
  public void scanFile(JavaFileScannerContext context) {
    expectedLevel = 0;
    isBlockAlreadyReported = false;
    lastCheckedLine = 0;
    isInAnonymousClass.clear();
    super.scanFile(context);
  }

  @Override
  public void visitNode(Tree tree) {
    if (isClassTree(tree)) {
      ClassTree classTree = (ClassTree) tree;
      // Exclude anonymous classes
      isInAnonymousClass.push(classTree.simpleName() == null);
      if (!isInAnonymousClass.peek()) {
        checkIndentation(Collections.singletonList(classTree));
      }
    }
    expectedLevel += indentationLevel;
    isBlockAlreadyReported = false;
    checkCaseGroup(tree);
    checkClassTree(tree);
    checkBlock(tree);
  }

  private void checkCaseGroup(Tree tree) {
    if (tree.is(Kind.CASE_GROUP)) {
      List<CaseLabelTree> labels = ((CaseGroupTree) tree).labels();
      if (labels.size() >= 2) {
        CaseLabelTree previousCaseLabelTree = labels.get(labels.size() - 2);
        lastCheckedLine = LastSyntaxTokenFinder.lastSyntaxToken(previousCaseLabelTree).line();
      }
      checkIndentation(((CaseGroupTree) tree).body());
    }
  }

  private void checkClassTree(Tree tree) {
    if (isClassTree(tree)) {
      ClassTree classTree = (ClassTree) tree;
      // Exclude anonymous classes
      if (classTree.simpleName() != null) {
        checkIndentation(classTree.members());
      }
    }
  }

  private void checkBlock(Tree tree) {
    if (tree.is(Kind.BLOCK)) {
      if (tree.parent().is(Kind.LAMBDA_EXPRESSION)) {
        expectedLevel += indentationLevel;
      }
      checkIndentation(((BlockTree) tree).body());
      if (tree.parent().is(Kind.LAMBDA_EXPRESSION)) {
        expectedLevel -= indentationLevel;
      }
    }
  }

  private void checkIndentation(List<? extends Tree> trees) {
    for (Tree tree : trees) {
      SyntaxToken firstSyntaxToken = FirstSyntaxTokenFinder.firstSyntaxToken(tree);
      if (firstSyntaxToken.column() != expectedLevel && !isExcluded(tree, firstSyntaxToken.line())) {
        addIssue(tree, "Make this line start at column " + (expectedLevel + 1) + ".");
        isBlockAlreadyReported = true;
      }
      lastCheckedLine = LastSyntaxTokenFinder.lastSyntaxToken(tree).line();
    }
  }

  @Override
  public void leaveNode(Tree tree) {
    expectedLevel -= indentationLevel;
    isBlockAlreadyReported = false;
    lastCheckedLine = LastSyntaxTokenFinder.lastSyntaxToken(tree).line();
    if (isClassTree(tree)) {
      isInAnonymousClass.pop();
    }
  }

  private boolean isExcluded(Tree node, int nodeLine) {
    return node.is(Kind.ENUM_CONSTANT) || isBlockAlreadyReported || lastCheckedLine == nodeLine || isInAnonymousClass.peek();
  }

  private static boolean isClassTree(Tree tree) {
    return tree.is(Kind.CLASS, Kind.ENUM, Kind.INTERFACE, Kind.ANNOTATION_TYPE);
  }

}
