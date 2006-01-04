/******************************************************************************* * Copyright (c) 2006 IBM Corporation and others. * All rights reserved. This program and the accompanying materials * are made available under the terms of the Eclipse Public License v1.0 * which accompanies this distribution, and is available at * http://www.eclipse.org/legal/epl-v10.html * * Contributors: *     IBM Corporation - initial API and implementation ******************************************************************************/package org.eclipse.ui.internal.handlers;import java.util.ArrayList;import java.util.Collection;import java.util.List;import org.eclipse.core.commands.Category;import org.eclipse.core.commands.Command;import org.eclipse.core.commands.IState;import org.eclipse.core.commands.ParameterizedCommand;import org.eclipse.core.expressions.Expression;import org.eclipse.core.runtime.IConfigurationElement;import org.eclipse.core.runtime.IExtensionRegistry;import org.eclipse.core.runtime.IRegistryChangeEvent;import org.eclipse.core.runtime.Platform;import org.eclipse.jface.action.Action;import org.eclipse.jface.action.LegacyActionTools;import org.eclipse.jface.commands.RadioState;import org.eclipse.jface.commands.ToggleState;import org.eclipse.jface.menus.IMenuStateIds;import org.eclipse.ui.ISourceProvider;import org.eclipse.ui.LegacyHandlerSubmissionExpression;import org.eclipse.ui.SelectionEnabler;import org.eclipse.ui.commands.ICommandImageService;import org.eclipse.ui.commands.ICommandService;import org.eclipse.ui.handlers.IHandlerActivation;import org.eclipse.ui.handlers.IHandlerService;import org.eclipse.ui.internal.ActionExpression;import org.eclipse.ui.internal.WorkbenchMessages;import org.eclipse.ui.internal.menus.LegacyActionExpressionWrapper;import org.eclipse.ui.internal.menus.LegacyActionPersistence;import org.eclipse.ui.internal.menus.LegacyEditorContributionExpression;import org.eclipse.ui.internal.services.LegacySelectionEnablerWrapper;import org.eclipse.ui.internal.services.RegistryPersistence;import org.eclipse.ui.keys.IBindingService;/** * <p> * Reads the action-based extensions, and converts them into handlers. * </p> * <p> * This class is not intended for use outside of the * <code>org.eclipse.ui.workbench</code> plug-in. * </p> * <p> * <strong>EXPERIMENTAL</strong>. This class or interface has been added as * part of a work in progress. There is a guarantee neither that this API will * work nor that it will remain the same. Please do not use this API without * consulting with the Platform/UI team. * </p> *  * @since 3.2 */public final class LegacyActionHandlerPersistence extends RegistryPersistence {	/**	 * The index of the action set elements in the indexed array.	 * 	 * @see LegacyActionPersistence#read()	 */	private static final int INDEX_ACTION_SETS = 0;	/**	 * The index of the editor contribution elements in the indexed array.	 * 	 * @see LegacyActionPersistence#read()	 */	private static final int INDEX_EDITOR_CONTRIBUTIONS = 1;	/**	 * The index of the object contribution elements in the indexed array.	 * 	 * @see LegacyActionPersistence#read()	 */	private static final int INDEX_OBJECT_CONTRIBUTIONS = 2;	/**	 * The index of the view contribution elements in the indexed array.	 * 	 * @see LegacyActionPersistence#read()	 */	private static final int INDEX_VIEW_CONTRIBUTIONS = 3;	/**	 * The index of the viewer contribution elements in the indexed array.	 * 	 * @see LegacyActionPersistence#read()	 */	private static final int INDEX_VIEWER_CONTRIBUTIONS = 4;	/**	 * The binding service which should be populated with bindings from actions;	 * must not be <code>null</code>.	 */	private final IBindingService bindingService;	/**	 * The command image service which should be populated with the images from	 * the actions; must not be <code>null</code>.	 */	private final ICommandImageService commandImageService;	/**	 * The command service which is providing the commands for the workbench;	 * must not be <code>null</code>.	 */	private final ICommandService commandService;	/**	 * The handler activations that have come from the registry. This is used to	 * flush the activations when the registry is re-read. This value is never	 * <code>null</code>	 */	private final Collection handlerActivations = new ArrayList();	/**	 * The service to which the handler should be added; must not be	 * <code>null</code>.	 */	private final IHandlerService handlerService;	/**	 * The event providers required to support the <code>IActionDelegate</code>	 * interface; never <code>null</code>.	 */	private final ISourceProvider[] sourceProviders;	/**	 * Constructs a new instance of {@link LegacyActionHandlerPersistence}.	 * 	 * @param commandService	 *            The command service which is providing the commands for the	 *            workbench; must not be <code>null</code>.	 * @param handlerService	 *            The service to which the handler should be added; must not be	 *            <code>null</code>.	 * @param bindingService	 *            The binding service which should be populated with bindings	 *            from actions; must not be <code>null</code>.	 * @param commandImageService	 *            The command image service which should be populated with the	 *            images from the actions; must not be <code>null</code>.	 * @param sourceProviders	 *            The event providers required to support the legacy	 *            <code>IActionDelegate</code>; must not be <code>null</code>.	 */	public LegacyActionHandlerPersistence(final ICommandService commandService,			final IHandlerService handlerService,			final IBindingService bindingService,			final ICommandImageService commandImageService,			final ISourceProvider[] sourceProviders) {		this.commandService = commandService;		this.handlerService = handlerService;		this.bindingService = bindingService;		this.commandImageService = commandImageService;		this.sourceProviders = sourceProviders;	}	/**	 * Deactivates all of the activations made by this class, and then clears	 * the collection. This should be called before every read.	 */	private final void clearActivations() {		handlerService.deactivateHandlers(handlerActivations);		handlerActivations.clear();	}	/**	 * Determine which command to use. This is slightly complicated as actions	 * do not have to have commands, but the new architecture requires it. As	 * such, we will auto-generate a command for the action if the definitionId	 * is missing or points to a command that does not yet exist. All such	 * command identifiers are prefixed with AUTOGENERATED_COMMAND_ID_PREFIX.	 * 	 * @param element	 *            The action element from which a command must be generated;	 *            must not be <code>null</code>.	 * @param primaryId	 *            The primary identifier to use when auto-generating a command;	 *            must not be <code>null</code>.	 * @param secondaryId	 *            The secondary identifier to use when auto-generating a	 *            command; must not be <code>null</code>.	 * @param warningsToLog	 *            The collection of warnings logged while reading the extension	 *            point; must not be <code>null</code>.	 * @return the fully-parameterized command; <code>null</code> if an error	 *         occurred.	 */	private final ParameterizedCommand convertActionToCommand(			final IConfigurationElement element, final String primaryId,			final String secondaryId, final List warningsToLog) {		String commandId = readOptional(element, ATTRIBUTE_DEFINITION_ID);		Command command = null;		if (commandId != null) {			command = commandService.getCommand(commandId);		}		String label = null;		if ((commandId == null) || (!command.isDefined())) {			if (commandId == null) {				commandId = AUTOGENERATED_PREFIX + primaryId + '/'						+ secondaryId;			}			// Read the label attribute.			label = readRequired(element, ATTRIBUTE_LABEL, warningsToLog,					"Actions require a non-empty label or definitionId", //$NON-NLS-1$					commandId);			if (label == null) {				label = WorkbenchMessages.LegacyActionPersistence_AutogeneratedCommandName;			}			/*			 * Read the tooltip attribute. The tooltip is really the description			 * of the command.			 */			final String tooltip = readOptional(element, ATTRIBUTE_TOOLTIP);			// Define the command.			command = commandService.getCommand(commandId);			final Category category = commandService.getCategory(null);			final String name = LegacyActionTools.removeAcceleratorText(Action					.removeMnemonics(label));			command.define(name, tooltip, category, null);			// TODO Decide the command state.			final String style = readOptional(element, ATTRIBUTE_STYLE);			if (STYLE_RADIO.equals(style)) {				final IState state = new RadioState();				// TODO How to set the id?				final boolean checked = readBoolean(element, ATTRIBUTE_STATE,						false);				state.setValue((checked) ? Boolean.TRUE : Boolean.FALSE);				command.addState(IMenuStateIds.STYLE, state);			} else if (STYLE_TOGGLE.equals(style)) {				final IState state = new ToggleState();				final boolean checked = readBoolean(element, ATTRIBUTE_STATE,						false);				state.setValue((checked) ? Boolean.TRUE : Boolean.FALSE);				command.addState(IMenuStateIds.STYLE, state);			}		}		return new ParameterizedCommand(command, null);	}	/**	 * <p>	 * Extracts the handler information from the given action element. These are	 * registered with the handler service. They are always active.	 * </p>	 * 	 * @param element	 *            The action element from which the handler should be read; must	 *            not be <code>null</code>.	 * @param actionId	 *            The identifier of the action for which a handler is being	 *            created; must not be <code>null</code>.	 * @param command	 *            The command for which this handler applies; must not be	 *            <code>null</code>.	 * @param activeWhenExpression	 *            The expression controlling when the handler is active; may be	 *            <code>null</code>.	 * @param viewId	 *            The view to which this handler is associated. This value is	 *            required if this is a view action; otherwise it can be	 *            <code>null</code>.	 */	private final void convertActionToHandler(			final IConfigurationElement element, final String actionId,			final ParameterizedCommand command,			final Expression activeWhenExpression, final String viewId) {		// Read the class attribute.		final String classString = readOptional(element, ATTRIBUTE_CLASS);		if (classString == null) {			return;		}		// Read the enablesFor attribute, and enablement and selection elements.		SelectionEnabler enabler = null;		if (element.getAttribute(ATTRIBUTE_ENABLES_FOR) != null) {			enabler = new SelectionEnabler(element);		} else {			IConfigurationElement[] kids = element					.getChildren(ELEMENT_ENABLEMENT);			if (kids.length > 0)				enabler = new SelectionEnabler(element);		}		final Expression enabledWhenExpression;		if (enabler == null) {			enabledWhenExpression = null;		} else {			enabledWhenExpression = new LegacySelectionEnablerWrapper(enabler);		}		/*		 * Create the handler. TODO The image style is read at the workbench		 * level, but it is hard to communicate this information to this point.		 * For now, I'll pass null, but this ultimately won't work.		 */		final ActionDelegateHandlerProxy handler = new ActionDelegateHandlerProxy(				element, ATTRIBUTE_CLASS, actionId, command, commandService,				handlerService, bindingService, commandImageService, null,				enabledWhenExpression, viewId);		for (int i = 0; i < sourceProviders.length; i++) {			final ISourceProvider provider = sourceProviders[i];			handler.addSourceProvider(provider);		}		// Activate the handler.		final String commandId = command.getId();		final IHandlerActivation handlerActivation;		if (activeWhenExpression == null) {			handlerActivation = handlerService.activateHandler(commandId,					handler);		} else {			handlerActivation = handlerService.activateHandler(commandId,					handler, activeWhenExpression);		}		handlerActivations.add(handlerActivation);	}	protected final boolean isChangeImportant(final IRegistryChangeEvent event) {		// TODO Write something to narrow this down a bit.		return true;	}	/**	 * <p>	 * Reads all of the actions from the deprecated extension points. Actions	 * have been replaced with commands, command images, handlers, menu elements	 * and action sets.	 * </p>	 * <p>	 * TODO Before this method is called, all of the extension points must be	 * cleared.	 * </p>	 */	public final void read() {		super.read();		// Create the extension registry mementos.		final IExtensionRegistry registry = Platform.getExtensionRegistry();		int actionSetCount = 0;		int editorContributionCount = 0;		int objectContributionCount = 0;		int viewContributionCount = 0;		int viewerContributionCount = 0;		final IConfigurationElement[][] indexedConfigurationElements = new IConfigurationElement[5][];		// Sort the actionSets extension point.		final IConfigurationElement[] actionSetsExtensionPoint = registry				.getConfigurationElementsFor(EXTENSION_ACTION_SETS);		for (int i = 0; i < actionSetsExtensionPoint.length; i++) {			final IConfigurationElement element = actionSetsExtensionPoint[i];			final String name = element.getName();			if (ELEMENT_ACTION_SET.equals(name)) {				addElementToIndexedArray(element, indexedConfigurationElements,						INDEX_ACTION_SETS, actionSetCount++);			}		}		// Sort the editorActions extension point.		final IConfigurationElement[] editorActionsExtensionPoint = registry				.getConfigurationElementsFor(EXTENSION_EDITOR_ACTIONS);		for (int i = 0; i < editorActionsExtensionPoint.length; i++) {			final IConfigurationElement element = editorActionsExtensionPoint[i];			final String name = element.getName();			if (ELEMENT_EDITOR_CONTRIBUTION.equals(name)) {				addElementToIndexedArray(element, indexedConfigurationElements,						INDEX_EDITOR_CONTRIBUTIONS, editorContributionCount++);			}		}		// Sort the popupMenus extension point.		final IConfigurationElement[] popupMenusExtensionPoint = registry				.getConfigurationElementsFor(EXTENSION_POPUP_MENUS);		for (int i = 0; i < popupMenusExtensionPoint.length; i++) {			final IConfigurationElement element = popupMenusExtensionPoint[i];			final String name = element.getName();			if (ELEMENT_OBJECT_CONTRIBUTION.equals(name)) {				addElementToIndexedArray(element, indexedConfigurationElements,						INDEX_OBJECT_CONTRIBUTIONS, objectContributionCount++);			} else if (ELEMENT_VIEWER_CONTRIBUTION.equals(name)) {				addElementToIndexedArray(element, indexedConfigurationElements,						INDEX_VIEWER_CONTRIBUTIONS, viewerContributionCount++);			}		}		// Sort the viewActions extension point.		final IConfigurationElement[] viewActionsExtensionPoint = registry				.getConfigurationElementsFor(EXTENSION_VIEW_ACTIONS);		for (int i = 0; i < viewActionsExtensionPoint.length; i++) {			final IConfigurationElement element = viewActionsExtensionPoint[i];			final String name = element.getName();			if (ELEMENT_VIEW_CONTRIBUTION.equals(name)) {				addElementToIndexedArray(element, indexedConfigurationElements,						INDEX_VIEW_CONTRIBUTIONS, viewContributionCount++);			}		}		clearActivations();		readActionSets(indexedConfigurationElements[INDEX_ACTION_SETS],				actionSetCount);		readEditorContributions(				indexedConfigurationElements[INDEX_EDITOR_CONTRIBUTIONS],				editorContributionCount);		readObjectContributions(				indexedConfigurationElements[INDEX_OBJECT_CONTRIBUTIONS],				objectContributionCount);		readViewContributions(				indexedConfigurationElements[INDEX_VIEW_CONTRIBUTIONS],				viewContributionCount);		readViewerContributions(				indexedConfigurationElements[INDEX_VIEWER_CONTRIBUTIONS],				viewerContributionCount);	}	/**	 * Reads the actions, and defines all the necessary subcomponents in terms	 * of the command architecture. For each action, there could be a command, a	 * command image binding, a handler and a menu item.	 * 	 * @param primaryId	 *            The identifier of the primary object to which this action	 *            belongs. This is used to auto-generate command identifiers	 *            when required. The <code>primaryId</code> must not be	 *            <code>null</code>.	 * @param elements	 *            The action elements to be read; must not be <code>null</code>.	 * @param warningsToLog	 *            The collection of warnings while parsing this extension point;	 *            must not be <code>null</code>.	 * @param commandService	 *            The command manager for the workbench; must not be	 *            <code>null</code>.	 * @param handlerService	 *            The service to which the handler should be added; must not be	 *            <code>null</code>.	 * @param bindingService	 *            The binding manager for the workbench; must not be	 *            <code>null</code>.	 * @param commandImageService	 *            The command image manager for the workbench; must not be	 *            <code>null</code>.	 * @param menuService	 *            The menu service for the workbench; must not be	 *            <code>null</code>.	 * @param locationInfo	 *            The information required to create the non-leaf portion of the	 *            location element; may be <code>null</code> if there is no	 *            non-leaf component.	 * @param visibleWhenExpression	 *            The expression controlling visibility of the corresponding	 *            menu elements; may be <code>null</code>.	 * @param sourceProviders	 *            The event providers required to support the legacy	 *            <code>IActionDelegate</code>; must not be <code>null</code>.	 * @param viewId	 *            The view to which this handler is associated. This value is	 *            required if this is a view action; otherwise it can be	 *            <code>null</code>.	 */	private final void readActions(final String primaryId,			final IConfigurationElement[] elements, final List warningsToLog,			final Expression visibleWhenExpression, final String viewId) {		for (int i = 0; i < elements.length; i++) {			final IConfigurationElement element = elements[i];			/*			 * We might need the identifier to generate the command, so we'll			 * read it out now.			 */			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "Actions require an id"); //$NON-NLS-1$			if (id == null) {				continue;			}			// Try to break out the command part of the action.			final ParameterizedCommand command = convertActionToCommand(					element, primaryId, id, warningsToLog);			if (command == null) {				continue;			}			// TODO Read the helpContextId attribute			// TODO Read the overrideActionId attribute			convertActionToHandler(element, id, command, visibleWhenExpression,					viewId);		}	}	/**	 * Reads all of the action and menu child elements from the given element.	 * 	 * @param element	 *            The configuration element from which the actions and menus	 *            should be read; must not be <code>null</code>, but may be	 *            empty.	 * @param id	 *            The identifier of the contribution being made. This could be	 *            an action set, an editor contribution, a view contribution, a	 *            viewer contribution or an object contribution. This value must	 *            not be <code>null</code>.	 * @param warningsToLog	 *            The list of warnings already logged for this extension point;	 *            must not be <code>null</code>.	 * @param visibleWhenExpression	 *            The expression controlling visibility of the corresponding	 *            menu elements; may be <code>null</code>.	 * @param viewId	 *            The view to which this handler is associated. This value is	 *            required if this is a view action; otherwise it can be	 *            <code>null</code>.	 */	private final void readActionsAndMenus(final IConfigurationElement element,			final String id, final List warningsToLog,			final Expression visibleWhenExpression, final String viewId) {		// Read its child elements.		final IConfigurationElement[] actionElements = element				.getChildren(ELEMENT_ACTION);		readActions(id, actionElements, warningsToLog, visibleWhenExpression,				viewId);	}	/**	 * Reads the deprecated actions from an array of elements from the action	 * sets extension point.	 * 	 * @param configurationElements	 *            The configuration elements in the extension point; must not be	 *            <code>null</code>, but may be empty.	 * @param configurationElementCount	 *            The number of configuration elements that are really in the	 *            array.	 */	private final void readActionSets(			final IConfigurationElement[] configurationElements,			final int configurationElementCount) {		final List warningsToLog = new ArrayList(1);		for (int i = 0; i < configurationElementCount; i++) {			final IConfigurationElement element = configurationElements[i];			// Read the action set identifier.			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "Action sets need an id"); //$NON-NLS-1$			if (id == null)				continue;						// Restrict the handler to when the action set is active.			final ActionSetExpression expression = new ActionSetExpression(id);			// Read all of the child elements.			readActionsAndMenus(element, id, warningsToLog, expression, null);		}		logWarnings(				warningsToLog,				"Warnings while parsing the action sets from the 'org.eclipse.ui.actionSets' extension point"); //$NON-NLS-1$	}	/**	 * Reads the deprecated editor contributions from an array of elements from	 * the editor actions extension point.	 * 	 * @param configurationElements	 *            The configuration elements in the extension point; must not be	 *            <code>null</code>, but may be empty.	 * @param configurationElementCount	 *            The number of configuration elements that are really in the	 *            array.	 */	private final void readEditorContributions(			final IConfigurationElement[] configurationElements,			final int configurationElementCount) {		final List warningsToLog = new ArrayList(1);		for (int i = 0; i < configurationElementCount; i++) {			final IConfigurationElement element = configurationElements[i];			// Read the editor contribution identifier.			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "Editor contributions need an id"); //$NON-NLS-1$			if (id == null)				continue;			/*			 * Read the target id. This is the identifier of the editor with			 * which these contributions are associated.			 */			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,					warningsToLog, "Editor contributions need a target id", id); //$NON-NLS-1$			if (targetId == null)				continue;			final Expression visibleWhenExpression = new LegacyEditorContributionExpression(					targetId);			// Read all of the child elements from the registry.			readActionsAndMenus(element, id, warningsToLog,					visibleWhenExpression, null);		}		logWarnings(				warningsToLog,				"Warnings while parsing the editor contributions from the 'org.eclipse.ui.editorActions' extension point"); //$NON-NLS-1$	}	/**	 * Reads the deprecated object contributions from an array of elements from	 * the popup menus extension point.	 * 	 * @param configurationElements	 *            The configuration elements in the extension point; must not be	 *            <code>null</code>, but may be empty.	 * @param configurationElementCount	 *            The number of configuration elements that are really in the	 *            array.	 */	private final void readObjectContributions(			final IConfigurationElement[] configurationElements,			final int configurationElementCount) {		final List warningsToLog = new ArrayList(1);		for (int i = 0; i < configurationElementCount; i++) {			final IConfigurationElement element = configurationElements[i];			// Read the object contribution identifier.			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "Object contributions need an id"); //$NON-NLS-1$			if (id == null)				continue;			// Read the object class. This influences the visibility.			final String objectClass = readRequired(element,					ATTRIBUTE_OBJECT_CLASS, warningsToLog,					"Object contributions need an object class", id); //$NON-NLS-1$			if (objectClass == null)				continue;			// TODO Read the name filter. This influences the visibility.			// final String nameFilter = readOptional(element,			// ATTRIBUTE_NAME_FILTER);			// TODO Read the object class. This influences the visibility.			// final boolean adaptable = readBoolean(element,			// ATTRIBUTE_ADAPTABLE,			// false);			// TODO Read the filter elements.			// TODO Read the enablement elements.			// TODO Figure out an appropriate visibility expression.			// Read the visibility element, if any.			final Expression visibleWhenExpression = readVisibility(element,					id, warningsToLog);			// Read all of the child elements from the registry.			readActionsAndMenus(element, id, warningsToLog,					visibleWhenExpression, null);		}		logWarnings(				warningsToLog,				"Warnings while parsing the object contributions from the 'org.eclipse.ui.popupMenus' extension point"); //$NON-NLS-1$	}	/**	 * Reads the deprecated view contributions from an array of elements from	 * the view actions extension point.	 * 	 * @param configurationElements	 *            The configuration elements in the extension point; must not be	 *            <code>null</code>, but may be empty.	 * @param configurationElementCount	 *            The number of configuration elements that are really in the	 *            array.	 */	private final void readViewContributions(			final IConfigurationElement[] configurationElements,			final int configurationElementCount) {		final List warningsToLog = new ArrayList(1);		for (int i = 0; i < configurationElementCount; i++) {			final IConfigurationElement element = configurationElements[i];			// Read the view contribution identifier.			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "View contributions need an id"); //$NON-NLS-1$			if (id == null)				continue;			/*			 * Read the target id. This is the identifier of the view with which			 * these contributions are associated.			 */			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,					warningsToLog, "View contributions need a target id", id); //$NON-NLS-1$			if (targetId == null)				continue;			final Expression visibleWhenExpression = new LegacyHandlerSubmissionExpression(					targetId, null, null);			// Read all of the child elements from the registry.			readActionsAndMenus(element, id, warningsToLog,					visibleWhenExpression, targetId);		}		logWarnings(				warningsToLog,				"Warnings while parsing the view contributions from the 'org.eclipse.ui.viewActions' extension point"); //$NON-NLS-1$	}	/**	 * Reads the deprecated viewer contributions from an array of elements from	 * the popup menus extension point.	 * 	 * @param configurationElements	 *            The configuration elements in the extension point; must not be	 *            <code>null</code>, but may be empty.	 * @param configurationElementCount	 *            The number of configuration elements that are really in the	 *            array.	 */	private final void readViewerContributions(			final IConfigurationElement[] configurationElements,			final int configurationElementCount) {		final List warningsToLog = new ArrayList(1);		for (int i = 0; i < configurationElementCount; i++) {			final IConfigurationElement element = configurationElements[i];			// Read the viewer contribution identifier.			final String id = readRequired(element, ATTRIBUTE_ID,					warningsToLog, "Viewer contributions need an id"); //$NON-NLS-1$			if (id == null)				continue;						// Read the target identifier.			final String targetId = readRequired(element, ATTRIBUTE_TARGET_ID,					warningsToLog, "Viewer contributions need a target id", id); //$NON-NLS-1$			if (targetId == null) {				continue;			}			// Read the visibility element, if any.			final Expression visibleWhenExpression = readVisibility(element,					id, warningsToLog);			final Expression menuVisibleWhenExpression = new ViewerContributionExpression(					targetId, visibleWhenExpression);			// Read all of the child elements from the registry.			readActionsAndMenus(element, id, warningsToLog,					menuVisibleWhenExpression, null);		}		logWarnings(				warningsToLog,				"Warnings while parsing the viewer contributions from the 'org.eclipse.ui.popupMenus' extension point"); //$NON-NLS-1$	}	/**	 * Reads the visibility element for a contribution from the	 * <code>org.eclipse.ui.popupMenus</code> extension point.	 * 	 * @param parentElement	 *            The contribution element which contains a visibility	 *            expression; must not be <code>null</code>.	 * @param parentId	 *            The identifier of the parent contribution; may be	 *            <code>null</code>.	 * @param warningsToLog	 *            The list of warnings to be logged; must not be	 *            <code>null</code>.	 * @return An expression representing the visibility element; may be	 *         <code>null</code>.	 */	private final Expression readVisibility(			final IConfigurationElement parentElement, final String parentId,			final List warningsToLog) {		final IConfigurationElement[] visibilityElements = parentElement				.getChildren(ELEMENT_VISIBILITY);		if ((visibilityElements == null) || (visibilityElements.length == 0)) {			return null;		}		if (visibilityElements.length != 1) {			addWarning(warningsToLog,					"There can only be one visibility element", parentElement, //$NON-NLS-1$					parentId);		}		final IConfigurationElement visibilityElement = visibilityElements[0];		final ActionExpression visibilityActionExpression = new ActionExpression(				visibilityElement);		final LegacyActionExpressionWrapper wrapper = new LegacyActionExpressionWrapper(				visibilityActionExpression);		return wrapper;	}}