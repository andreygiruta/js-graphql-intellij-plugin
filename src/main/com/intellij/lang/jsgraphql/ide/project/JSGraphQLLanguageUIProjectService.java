/**
 *  Copyright (c) 2015, Jim Kynde Meyer
 *  All rights reserved.
 *
 *  This source code is licensed under the MIT license found in the
 *  LICENSE file in the root directory of this source tree.
 */
package com.intellij.lang.jsgraphql.ide.project;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.execution.filters.UrlFilter;
import com.intellij.execution.process.OSProcessHandler;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.icons.AllIcons;
import com.intellij.json.JsonFileType;
import com.intellij.lang.jsgraphql.JSGraphQLFileType;
import com.intellij.lang.jsgraphql.icons.JSGraphQLIcons;
import com.intellij.lang.jsgraphql.ide.actions.JSGraphQLEditEndpointsAction;
import com.intellij.lang.jsgraphql.ide.actions.JSGraphQLExecuteEditorAction;
import com.intellij.lang.jsgraphql.ide.actions.JSGraphQLToggleVariablesAction;
import com.intellij.lang.jsgraphql.ide.configuration.JSGraphQLConfigurationProvider;
import com.intellij.lang.jsgraphql.ide.endpoints.JSGraphQLEndpoint;
import com.intellij.lang.jsgraphql.ide.endpoints.JSGraphQLEndpointsModel;
import com.intellij.lang.jsgraphql.languageservice.JSGraphQLNodeLanguageServiceClient;
import com.intellij.lang.jsgraphql.languageservice.JSGraphQLNodeLanguageServiceInstance;
import com.intellij.lang.jsgraphql.psi.JSGraphQLFile;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.impl.EditorHeaderComponent;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.fileEditor.impl.text.PsiAwareTextEditorProvider;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.testFramework.LightVirtualFile;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SideBorder;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.impl.ContentImpl;
import com.intellij.util.ui.UIUtil;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.params.HttpClientParams;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.StopWatch;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Provides the project-specific GraphQL tool window, including errors view, console, and query result editor.
 */
public class JSGraphQLLanguageUIProjectService implements Disposable, FileEditorManagerListener {

    private final static Logger log = Logger.getInstance(JSGraphQLLanguageUIProjectService.class);

    public final static String GRAPH_QL_TOOL_WINDOW_NAME = "GraphQL";
    public static final String GRAPH_QL_VARIABLES_JSON = "GraphQL.variables.json";

    /**
     * Indicates that this virtual file backs a GraphQL variables editor
     */
    public static final Key<Boolean> IS_GRAPH_QL_VARIABLES_VIRTUAL_FILE = Key.create(GRAPH_QL_VARIABLES_JSON);

    /**
     * Gets the variables editor associated with a .graphql query editor
     */
    public static final Key<Editor> GRAPH_QL_VARIABLES_EDITOR = Key.create(GRAPH_QL_VARIABLES_JSON+".variables.editor");

    /**
     * Gets the query editor associated with a GraphQL variables editor
     */
    public static final Key<Editor> GRAPH_QL_QUERY_EDITOR = Key.create(GRAPH_QL_VARIABLES_JSON+".query.editor");

    public final static Key<JSGraphQLEndpointsModel> JS_GRAPH_QL_ENDPOINTS_MODEL = Key.create("JSGraphQLEndpointsModel");

    public final static Key<Boolean> JS_GRAPH_QL_EDITOR_QUERYING = Key.create("JSGraphQLEditorQuerying");

    private static final String FILE_URL_PROPERTY = "fileUrl";

    private final JSGraphQLLanguageCompilerToolWindowManager myToolWindowManager;

    @NotNull
    private final Project myProject;

    private final Object myLock = new Object();

    private JSGraphQLConfigurationProvider configurationProvider;

    private FileEditor fileEditor;
    private JBLabel queryResultLabel;
    private JBLabel querySuccessLabel;

    public JSGraphQLLanguageUIProjectService(@NotNull final Project project) {

        myProject = project;
        configurationProvider = new JSGraphQLConfigurationProvider(myProject, this::reloadEndpoints);

        // the restart action
        AnAction restartInstanceAction = new AnAction("Restart JS GraphQL Language Service", "Restarts the JS GraphQL Language Service Node.js process", AllIcons.Javaee.UpdateRunningApplication) {
            public void actionPerformed(AnActionEvent e) {
                restartInstance();
            }
        };

        // tool window
        myToolWindowManager = new JSGraphQLLanguageCompilerToolWindowManager(project, GRAPH_QL_TOOL_WINDOW_NAME, GRAPH_QL_TOOL_WINDOW_NAME, JSGraphQLIcons.UI.GraphQLNode, restartInstanceAction);
        Disposer.register(this, this.myToolWindowManager);

        // listen for editor file tab changes to update the list of current errors
        project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this);

        // finally init the tool window tabs
        initToolWindow();

    }


    public static JSGraphQLLanguageUIProjectService getService(@NotNull Project project) {
        return ServiceManager.getService(project, JSGraphQLLanguageUIProjectService.class);
    }

    public static void showConsole(@NotNull Project project) {
        showToolWindowContent(project, ConsoleView.class);
    }

    private static void showToolWindowContent(@NotNull Project project, @NotNull Class<?> contentClass) {
        UIUtil.invokeLaterIfNeeded(() -> {
            final ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(GRAPH_QL_TOOL_WINDOW_NAME);
            if(toolWindow != null) {
                toolWindow.show(() -> {
                    for (Content content : toolWindow.getContentManager().getContents()) {
                        if(contentClass.isAssignableFrom(content.getComponent().getClass())) {
                            toolWindow.getContentManager().setSelectedContent(content);
                            break;
                        }
                    }
                });
            }
        });
    }

    public void connectToProcessHandler(OSProcessHandler processHandler) {
        UIUtil.invokeLaterIfNeeded(() -> {
            myToolWindowManager.connectToProcessHandler(processHandler);
            attachMessageFilter();
            processHandler.addProcessListener(new ProcessAdapter() {
                public void onTextAvailable(ProcessEvent event, Key outputType) {
                    if(outputType == ProcessOutputTypes.STDERR && !StringUtil.isEmpty(event.getText())) {
                        showConsole(myProject);
                    }
                }
            });
        });

    }


    // ---- editor tabs listener ----

    @Override
    public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        insertEditorHeaderComponentIfApplicable(source, file);
    }

    @Override
    public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
    }

    @Override
    public void selectionChanged(@NotNull FileEditorManagerEvent event) {
    }


    // ---- implementation ----


    // -- endpoints --

    private void reloadEndpoints(List<JSGraphQLEndpoint> newEndpoints) {
        final FileEditorManager fileEditorManager = FileEditorManager.getInstance(myProject);
        for (VirtualFile file : fileEditorManager.getOpenFiles()) {
            for (FileEditor editor : fileEditorManager.getEditors(file)) {
                if(editor instanceof TextEditor) {
                    final JSGraphQLEndpointsModel endpointsModel = ((TextEditor) editor).getEditor().getUserData(JS_GRAPH_QL_ENDPOINTS_MODEL);
                    if(endpointsModel != null) {
                        endpointsModel.reload(newEndpoints);
                    }
                }
            }
        }
    }

    // -- editor header component --

    private void insertEditorHeaderComponentIfApplicable(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
        if(file.getFileType() == JSGraphQLFileType.INSTANCE) {
            FileEditor fileEditor = source.getSelectedEditor(file);
            if (fileEditor instanceof TextEditor) {
                final Editor editor = ((TextEditor) fileEditor).getEditor();
                if(editor.getHeaderComponent() instanceof JSGraphQLEditorHeaderComponent) {
                    return;
                }
                final JComponent headerComponent = createHeaderComponent(fileEditor, editor);
                editor.setHeaderComponent(headerComponent);
                if(editor instanceof EditorEx) {
                    ((EditorEx) editor).setPermanentHeaderComponent(headerComponent);
                }
                editor.getScrollingModel().scrollVertically(-1000);
            }
        }
    }

    private static class JSGraphQLEditorHeaderComponent extends EditorHeaderComponent {}

    private JComponent createHeaderComponent(FileEditor fileEditor, Editor editor) {

        final JSGraphQLEditorHeaderComponent headerComponent = new JSGraphQLEditorHeaderComponent();

        // variables & settings actions
        final DefaultActionGroup settingsActions = new DefaultActionGroup();
        settingsActions.add(new JSGraphQLEditEndpointsAction());
        settingsActions.add(new JSGraphQLToggleVariablesAction());

        final JComponent settingsToolbar = createToolbar(settingsActions);
        headerComponent.add(settingsToolbar, BorderLayout.WEST);

        // query execute
        final DefaultActionGroup queryActions = new DefaultActionGroup();
        final AnAction executeGraphQLAction = ActionManager.getInstance().getAction(JSGraphQLExecuteEditorAction.class.getName());
        queryActions.add(executeGraphQLAction);
        final JComponent queryToolbar = createToolbar(queryActions);

        // configured endpoints combo box
        final JSGraphQLEndpointsModel endpointsModel = new JSGraphQLEndpointsModel(configurationProvider.getEndpoints());
        final ComboBox endpointComboBox = new ComboBox(endpointsModel);
        endpointComboBox.setToolTipText("GraphQL endpoint");
        editor.putUserData(JS_GRAPH_QL_ENDPOINTS_MODEL, endpointsModel);
        final JPanel endpointComboBoxPanel = new JPanel(new BorderLayout());
        endpointComboBoxPanel.setBorder(BorderFactory.createEmptyBorder(1, 2, 2, 2));
        endpointComboBoxPanel.add(endpointComboBox);

        // splitter to resize endpoints
        final OnePixelSplitter splitter = new OnePixelSplitter(false, .25F);
        splitter.setBorder(BorderFactory.createEmptyBorder());
        splitter.setFirstComponent(endpointComboBoxPanel);
        splitter.setSecondComponent(queryToolbar);
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setAndLoadSplitterProportionKey("JSGraphQLEndpointSplitterProportion");
        splitter.setOpaque(false);
        splitter.getDivider().setOpaque(false);

        headerComponent.add(splitter, BorderLayout.CENTER);

        // variables editor
        final LightVirtualFile virtualFile = new LightVirtualFile(GRAPH_QL_VARIABLES_JSON, JsonFileType.INSTANCE, "");
        final FileEditor variablesFileEditor = PsiAwareTextEditorProvider.getInstance().createEditor(myProject, virtualFile);
        final EditorEx variablesEditor = (EditorEx)((TextEditor)variablesFileEditor).getEditor();
        virtualFile.putUserData(IS_GRAPH_QL_VARIABLES_VIRTUAL_FILE, Boolean.TRUE);
        variablesEditor.setPlaceholder("{ variables }");
        variablesEditor.setShowPlaceholderWhenFocused(true);
        variablesEditor.getSettings().setRightMarginShown(false);
        variablesEditor.getSettings().setAdditionalLinesCount(0);
        variablesEditor.getSettings().setShowIntentionBulb(false);
        variablesEditor.getSettings().setFoldingOutlineShown(false);
        variablesEditor.getSettings().setLineNumbersShown(false);
        variablesEditor.getSettings().setLineMarkerAreaShown(false);
        variablesEditor.getSettings().setCaretRowShown(false);
        variablesEditor.putUserData(JS_GRAPH_QL_ENDPOINTS_MODEL, endpointsModel);

        // hide variables by default
        variablesEditor.getComponent().setVisible(false);

        // link the query and variables editor together
        variablesEditor.putUserData(GRAPH_QL_QUERY_EDITOR, editor);
        editor.putUserData(GRAPH_QL_VARIABLES_EDITOR, variablesEditor);

        final NonOpaquePanel variablesPanel = new NonOpaquePanel(variablesFileEditor.getComponent());
        variablesPanel.setBorder(IdeBorderFactory.createBorder(SideBorder.TOP));

        Disposer.register(fileEditor, variablesFileEditor);

        headerComponent.add(variablesPanel, BorderLayout.SOUTH);

        return headerComponent;
    }

    private JComponent createToolbar(ActionGroup actionGroup) {
        final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.EDITOR_TOOLBAR, actionGroup, true);
        toolbar.setReservePlaceAutoPopupIcon(false); // don't want space after the last button
        final JComponent component = toolbar.getComponent();
        component.setBorder(BorderFactory.createEmptyBorder());
        return component;
    }

    public void executeGraphQL(Editor editor, VirtualFile virtualFile) {
        final JSGraphQLEndpointsModel endpointsModel = editor.getUserData(JS_GRAPH_QL_ENDPOINTS_MODEL);
        if(endpointsModel != null) {
            final JSGraphQLEndpoint selectedEndpoint = endpointsModel.getSelectedItem();
            if(selectedEndpoint != null && selectedEndpoint.url != null) {
                final String buffer = editor.getDocument().getText();
                final Map<String, Object> requestData = Maps.newLinkedHashMap();
                requestData.put("query", buffer);
                requestData.put("variables", getQueryVariables(editor));
                final String requestJson = new Gson().toJson(requestData);
                final HttpClient httpClient = new HttpClient(new HttpClientParams());
                try {
                    final PostMethod method = new PostMethod(selectedEndpoint.url);
                    setHeadersFromOptions(selectedEndpoint, method);
                    method.setRequestEntity(new StringRequestEntity(requestJson, "application/json", "UTF-8"));
                    ApplicationManager.getApplication().executeOnPooledThread(() -> {
                        try {
                            try {
                                editor.putUserData(JS_GRAPH_QL_EDITOR_QUERYING, true);
                                StopWatch sw = new StopWatch();
                                sw.start();
                                httpClient.executeMethod(method);
                                final String responseJson = Optional.fromNullable(method.getResponseBodyAsString()).or("");
                                sw.stop();
                                final Integer errorCount = getErrorCount(responseJson);
                                if (fileEditor instanceof TextEditor) {
                                    final TextEditor textEditor = (TextEditor) fileEditor;
                                    UIUtil.invokeLaterIfNeeded(() -> {
                                        ApplicationManager.getApplication().runWriteAction(() -> {
                                            textEditor.getEditor().getDocument().setText(responseJson);
                                        });
                                        final StringBuilder queryResultText = new StringBuilder(virtualFile.getName()).
                                                append(": ").
                                                append(sw.getTime()).
                                                append(" ms execution time, ").
                                                append(bytesToDisplayString(responseJson.length())).
                                                append(" response");

                                        if(errorCount != null && errorCount > 0) {
                                            queryResultText.append(", ").append(errorCount).append(" error").append(errorCount > 1 ? "s" : "");
                                        }

                                        queryResultLabel.setText(queryResultText.toString());
                                        queryResultLabel.putClientProperty(FILE_URL_PROPERTY, virtualFile.getUrl());
                                        if (!queryResultLabel.isVisible()) {
                                            queryResultLabel.setVisible(true);
                                        }

                                        querySuccessLabel.setVisible(errorCount != null);
                                        if(querySuccessLabel.isVisible()) {
                                            if(errorCount == 0) {
                                                querySuccessLabel.setBorder(BorderFactory.createEmptyBorder(2, 8, 0, 0));
                                                querySuccessLabel.setIcon(AllIcons.General.InspectionsOK);
                                            } else {
                                                querySuccessLabel.setBorder(BorderFactory.createEmptyBorder(2, 12, 0, 4));
                                                querySuccessLabel.setIcon(AllIcons.Ide.ErrorPoint);
                                            }
                                        }
                                        showToolWindowContent(myProject, fileEditor.getComponent().getClass());
                                        textEditor.getEditor().getScrollingModel().scrollVertically(0);
                                    });
                                }
                            } finally {
                                editor.putUserData(JS_GRAPH_QL_EDITOR_QUERYING, null);
                            }
                        } catch (IOException e) {
                            Notifications.Bus.notify(new Notification("GraphQL", "GraphQL Query Error", selectedEndpoint.url + ": " + e.getMessage(), NotificationType.WARNING), myProject);
                        }
                    });
                } catch (UnsupportedEncodingException | IllegalStateException | IllegalArgumentException e) {
                    Notifications.Bus.notify(new Notification("GraphQL", "GraphQL Query Error", selectedEndpoint.url + ": " + e.getMessage(), NotificationType.ERROR), myProject);
                }

            }
        }
    }

    private Integer getErrorCount(String responseJson) {
        try {
            final Map res = new Gson().fromJson(responseJson, Map.class);
            if(res != null) {
                final Object errors = res.get("errors");
                if(errors instanceof Collection) {
                    return ((Collection) errors).size();
                }
                return 0;
            }
        } catch (JsonSyntaxException ignored) {
        }
        return null;
    }

    private String getQueryVariables(Editor editor) {
        final Editor variablesEditor = editor.getUserData(GRAPH_QL_VARIABLES_EDITOR);
        if(variablesEditor != null) {
            final String variables = variablesEditor.getDocument().getText();
            if(StringUtils.isNotEmpty(variables)) {
                return variables;
            }
        }
        return null;
    }

    private static String bytesToDisplayString(long bytes) {
        if (bytes < 1000) return bytes + " bytes";
        int exp = (int) (Math.log(bytes) / Math.log(1000));
        String pre = ("kMGTPE").charAt(exp-1)+"";
        return String.format("%.1f %sb", bytes / Math.pow(1000, exp), pre);
    }

    private void setHeadersFromOptions(JSGraphQLEndpoint endpoint, PostMethod method) {
        if(endpoint.options != null) {
            final Object headers = endpoint.options.get("headers");
            if(headers instanceof Map) {
                Map<String, Object> headersMap = (Map<String, Object>)headers;
                for (Map.Entry<String, Object> entry : headersMap.entrySet()) {
                    method.setRequestHeader(entry.getKey(), String.valueOf(entry.getValue()));
                }
            }
        }
    }


    // -- instance management --

    private void restartInstance() {

        synchronized(this.myLock) {
            final JSGraphQLNodeLanguageServiceInstance instance = JSGraphQLNodeLanguageServiceClient.getLanguageServiceInstance(myProject);
            if(instance != null) {
                if(myToolWindowManager != null) {
                    myToolWindowManager.disconnectFromProcessHandler();
                }
                instance.restart(() -> {
                    final Editor editor = FileEditorManager.getInstance(myProject).getSelectedTextEditor();
                    if(editor != null) {
                        final VirtualFile file = FileDocumentManager.getInstance().getFile(editor.getDocument());
                        if(file != null) {
                            final PsiManager psiManager = PsiManager.getInstance(myProject);
                            final PsiFile psiFile = psiManager.findFile(file);
                            if(psiFile != null) {
                                if(psiFile instanceof JSGraphQLFile) {
                                    DaemonCodeAnalyzer.getInstance(myProject).restart(psiFile);
                                }
                            }
                        }
                    }
                });
            }
        }
    }

    private void attachMessageFilter() {
        final ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(GRAPH_QL_TOOL_WINDOW_NAME);
        if(toolWindow != null) {
            for (Content content : toolWindow.getContentManager().getContents()) {
                if(content.getComponent() instanceof ConsoleView) {
                    ((ConsoleView) content.getComponent()).addMessageFilter(new UrlFilter());
                }
            }
        }
    }

    private void createToolWindowResultEditor(ToolWindow toolWindow) {

        final LightVirtualFile virtualFile = new LightVirtualFile("GraphQL.result.json", JsonFileType.INSTANCE, "");
        fileEditor = PsiAwareTextEditorProvider.getInstance().createEditor(myProject, virtualFile);

        if(fileEditor instanceof TextEditor) {
            final Editor editor = ((TextEditor) fileEditor).getEditor();
            final EditorEx editorEx = (EditorEx)editor;

            editorEx.getSettings().setShowIntentionBulb(false);
            editor.getSettings().setAdditionalLinesCount(0);
            editor.getSettings().setCaretRowShown(false);
            editor.getSettings().setBlinkCaret(false);

            // query result header
            final JSGraphQLEditorHeaderComponent header = new JSGraphQLEditorHeaderComponent();

            querySuccessLabel = new JBLabel();
            querySuccessLabel.setVisible(false);
            querySuccessLabel.setIconTextGap(0);
            header.add(querySuccessLabel, BorderLayout.WEST);

            queryResultLabel = new JBLabel("", null, SwingConstants.LEFT);
            queryResultLabel.setBorder(new EmptyBorder(4, 6, 4, 6));
            queryResultLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            queryResultLabel.setVisible(false);
            queryResultLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    final String fileUrl = (String)queryResultLabel.getClientProperty(FILE_URL_PROPERTY);
                    if(fileUrl != null) {
                        final VirtualFile queryFile = VirtualFileManager.getInstance().findFileByUrl(fileUrl);
                        if(queryFile != null) {
                            final FileEditorManager fileEditorManager = FileEditorManager.getInstance(myProject);
                            fileEditorManager.openFile(queryFile, true, true);
                        }
                    }
                }
            });
            header.add(queryResultLabel, BorderLayout.CENTER);

            // finally set the header as permanent such that it's restored after searches
            editor.setHeaderComponent(header);
            editorEx.setPermanentHeaderComponent(header);
        }

        Disposer.register(this, fileEditor);

        final ContentImpl content = new ContentImpl(fileEditor.getComponent(), "Query result", true);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);



    }

    private void initToolWindow() {
        if(this.myToolWindowManager != null && !this.myProject.isDisposed()) {
            StartupManager.getInstance(this.myProject).runWhenProjectIsInitialized(() -> ApplicationManager.getApplication().invokeLater(() -> {

                myToolWindowManager.init();

                final ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(GRAPH_QL_TOOL_WINDOW_NAME);
                if(toolWindow != null) {
                    // don't want the console and the current errors to be closed
                    for (Content content : toolWindow.getContentManager().getContents()) {
                        content.setCloseable(false);
                    }

                    createToolWindowResultEditor(toolWindow);
                }
            }, myProject.getDisposed()));
        }
    }

    @Override
    public void dispose() {}

}
