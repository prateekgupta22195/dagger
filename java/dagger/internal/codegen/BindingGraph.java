/*
 * Copyright (C) 2014 The Dagger Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dagger.internal.codegen;

import static dagger.internal.codegen.ComponentRequirement.Kind.BOUND_INSTANCE;
import static dagger.internal.codegen.DaggerStreams.toImmutableSet;
import static javax.lang.model.element.Modifier.ABSTRACT;

import com.google.auto.value.AutoValue;
import com.google.auto.value.extension.memoized.Memoized;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.graph.Traverser;
import dagger.Subcomponent;
import dagger.internal.codegen.ComponentDescriptor.BuilderRequirementMethod;
import dagger.model.DependencyRequest;
import dagger.model.Key;
import dagger.model.RequestKind;
import dagger.model.Scope;
import dagger.releasablereferences.CanReleaseReferences;
import dagger.releasablereferences.ReleasableReferenceManager;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.lang.model.element.TypeElement;

/**
 * The canonical representation of a full-resolved graph.
 *
 * @author Gregory Kick
 */
@AutoValue
abstract class BindingGraph {
  abstract ComponentDescriptor componentDescriptor();

  /**
   * The resolved bindings for all {@link ContributionBinding}s in this graph, keyed by {@link Key}.
   */
  // TODO(ronshapiro): when MembersInjectionBinding no longer extends Binding, rename this to
  // bindings()
  abstract ImmutableMap<Key, ResolvedBindings> contributionBindings();

  /**
   * The resolved bindings for all {@link MembersInjectionBinding}s in this graph, keyed by {@link
   * Key}.
   */
  abstract ImmutableMap<Key, ResolvedBindings> membersInjectionBindings();

  /**
   * Returns the {@link ResolvedBindings resolved bindings} instance for {@code key}. If {@code
   * requestKind} is {@link RequestKind#MEMBERS_INJECTION}, a {@link ResolvedBindings} with
   * {@linkplain #membersInjectionBindings() members injection bindings} will be returned, otherwise
   * a {@link ResolvedBindings} with {@link #contributionBindings()} will be returned.
   */
  final ResolvedBindings resolvedBindings(RequestKind requestKind, Key key) {
    return requestKind.equals(RequestKind.MEMBERS_INJECTION)
        ? membersInjectionBindings().get(key)
        : contributionBindings().get(key);
  }

  @Memoized
  ImmutableSet<ResolvedBindings> resolvedBindings() {
    return ImmutableSet.<ResolvedBindings>builder()
        .addAll(membersInjectionBindings().values())
        .addAll(contributionBindings().values())
        .build();
  }

  abstract ImmutableSet<BindingGraph> subgraphs();

  /**
   * The scopes in the graph that {@linkplain CanReleaseReferences can release their references} for
   * which there is a dependency request for any of the following:
   *
   * <ul>
   *   <li>{@code @ForReleasableReferences(scope)} {@link ReleasableReferenceManager}
   *   <li>{@code @ForReleasableReferences(scope)} {@code TypedReleasableReferenceManager<M>}, where
   *       {@code M} is the releasable-references metatadata type for {@code scope}
   *   <li>{@code Set<ReleasableReferenceManager>}
   *   <li>{@code Set<TypedReleasableReferenceManager<M>>}, where {@code M} is the metadata type for
   *       the scope
   * </ul>
   *
   * <p>This set is always empty for subcomponent graphs.
   */
  abstract ImmutableSet<Scope> scopesRequiringReleasableReferenceManagers();

  /** Returns the resolved bindings for the dependencies of {@code binding}. */
  ImmutableSet<ResolvedBindings> resolvedDependencies(ContributionBinding binding) {
    return binding
        .dependencies()
        .stream()
        .map(DependencyRequest::key)
        .map(
            key ->
                contributionBindings()
                    .getOrDefault(key, ResolvedBindings.noBindings(key, componentDescriptor())))
        .collect(toImmutableSet());
  }
  /**
   * The type that defines the component for this graph.
   *
   * @see ComponentDescriptor#componentDefinitionType()
   */
  TypeElement componentType() {
    return componentDescriptor().componentDefinitionType();
  }

  /**
   * Returns the set of modules that are owned by this graph regardless of whether or not any of
   * their bindings are used in this graph. For graphs representing top-level {@link
   * dagger.Component components}, this set will be the same as
   * {@linkplain ComponentDescriptor#transitiveModules the component's transitive modules}. For
   * {@linkplain Subcomponent subcomponents}, this set will be the transitive modules that are not
   * owned by any of their ancestors.
   */
  abstract ImmutableSet<ModuleDescriptor> ownedModules();

  ImmutableSet<TypeElement> ownedModuleTypes() {
    return FluentIterable.from(ownedModules()).transform(ModuleDescriptor::moduleElement).toSet();
  }

  private static final Traverser<BindingGraph> SUBGRAPH_TRAVERSER =
      Traverser.forTree(BindingGraph::subgraphs);

  /**
   * The types for which the component needs instances.
   *
   * <ul>
   *   <li>component dependencies
   *   <li>{@linkplain #ownedModules() owned modules} with concrete instance bindings that are used
   *       in the graph
   *   <li>bound instances
   * </ul>
   */
  @Memoized
  ImmutableSet<ComponentRequirement> componentRequirements() {
    ImmutableSet.Builder<ComponentRequirement> requirements = ImmutableSet.builder();
    StreamSupport.stream(SUBGRAPH_TRAVERSER.depthFirstPreOrder(this).spliterator(), false)
        .flatMap(graph -> graph.contributionBindings().values().stream())
        .flatMap(bindings -> bindings.contributionBindings().stream())
        .filter(ContributionBinding::requiresModuleInstance)
        .map(bindingDeclaration -> bindingDeclaration.contributingModule())
        .filter(Optional::isPresent)
        .map(Optional::get)
        .filter(module -> ownedModuleTypes().contains(module))
        .map(module -> ComponentRequirement.forModule(module.asType()))
        .forEach(requirements::add);
    componentDescriptor()
        .dependencies()
        .stream()
        .forEach(requirements::add);
    if (componentDescriptor().builderSpec().isPresent()) {
      componentDescriptor()
          .builderSpec()
          .get()
          .requirementMethods()
          .stream()
          .map(BuilderRequirementMethod::requirement)
          .filter(req -> req.kind().equals(BOUND_INSTANCE))
          .forEach(requirements::add);
    }
    return requirements.build();
  }

  /** Returns the {@link ComponentDescriptor}s for this component and its subcomponents. */
  ImmutableSet<ComponentDescriptor> componentDescriptors() {
    return FluentIterable.from(SUBGRAPH_TRAVERSER.depthFirstPreOrder(this))
        .transform(BindingGraph::componentDescriptor)
        .toSet();
  }

  ImmutableSet<ComponentRequirement> availableDependencies() {
    return Stream.concat(
            componentDescriptor()
                .transitiveModuleTypes()
                .stream()
                .filter(dep -> !dep.getModifiers().contains(ABSTRACT))
                .map(module -> ComponentRequirement.forModule(module.asType())),
            componentDescriptor().dependencies().stream())
        .collect(toImmutableSet());
  }

  @Memoized
  @Override
  public abstract int hashCode();

  @Override // Suppresses ErrorProne warning that hashCode was overridden w/o equals
  public abstract boolean equals(Object other);

  static BindingGraph create(
      ComponentDescriptor componentDescriptor,
      ImmutableMap<Key, ResolvedBindings> resolvedContributionBindingsMap,
      ImmutableMap<Key, ResolvedBindings> resolvedMembersInjectionBindings,
      ImmutableSet<BindingGraph> subgraphs,
      ImmutableSet<Scope> scopesRequiringReleasableReferenceManagers,
      ImmutableSet<ModuleDescriptor> ownedModules) {
    return new AutoValue_BindingGraph(
        componentDescriptor,
        resolvedContributionBindingsMap,
        resolvedMembersInjectionBindings,
        subgraphs,
        scopesRequiringReleasableReferenceManagers,
        ownedModules);
  }
}
