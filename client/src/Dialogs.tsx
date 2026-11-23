import { AddForm } from './AddForm';
import { ConfirmForm } from './ConfirmForm';
import { NewFileForm } from './NewFileForm';
import { ContextMenu } from './ContextMenu';
import { ExternalFunctionForm } from './ExternalFunctionForm';
import { PromptForm } from './PromptForm';
import type { DiagramMessage } from './protocol';
import { ParametersForm, type ParameterItem } from './ParametersForm';
import { SuperTypesForm } from './SuperTypesForm';
import type { Dialogs as DialogState } from './useDialogs';

/** Renders the open menu and form (see useDialogs). */
export function Dialogs({
  dialogs: d,
  diagram,
  busy,
}: {
  dialogs: DialogState;
  diagram: DiagramMessage | null;
  busy: boolean;
}) {
  return (
    <>
      {d.menu && <ContextMenu screen={d.menu.screen} items={d.menu.items} onClose={d.closeMenu} />}
      {d.prompt && (
        <PromptForm
          key={`${d.prompt.kind}:${d.prompt.id}`}
          request={d.prompt}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitPrompt}
          onCancel={d.cancelForm}
        />
      )}
      {d.externalForm && (
        <ExternalFunctionForm
          screen={d.externalForm}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitExternalFunction}
          onCancel={d.cancelForm}
        />
      )}
      {d.superTypesForm && diagram?.diagramType && (
        <SuperTypesForm
          typeName={diagram.diagramType}
          candidates={diagram.diagramTypes.filter((t) => t !== diagram.diagramType)}
          current={diagram.superTypes}
          screen={d.superTypesForm}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitSuperTypes}
          onCancel={d.cancelForm}
        />
      )}
      {d.parametersForm && diagram?.diagramType && (
        <ParametersForm
          typeName={diagram.diagramType}
          inParameters={parameters(diagram, 'inParameter')}
          outParameters={parameters(diagram, 'outParameter')}
          screen={d.parametersForm}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitParameters}
          onCancel={d.cancelForm}
        />
      )}
      {d.newFileForm && (
        <NewFileForm
          screen={d.newFileForm}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitNewFile}
          onCancel={d.cancelForm}
        />
      )}
      {d.deleteFileForm && diagram && (
        <ConfirmForm
          screen={d.deleteFileForm}
          title={`Delete ${diagram.file}?`}
          message="The file is deleted from the workspace. This cannot be undone."
          action="Delete"
          error={d.formError}
          busy={busy}
          onConfirm={d.submitDeleteFile}
          onCancel={d.cancelForm}
        />
      )}
      {d.addForm && (
        <AddForm
          key={`${d.addForm.tool}@${d.addForm.screen.x},${d.addForm.screen.y}`}
          request={d.addForm}
          error={d.formError}
          busy={busy}
          onSubmit={d.submitAddForm}
          onCancel={d.cancelForm}
        />
      )}
    </>
  );
}

/** The shown type's in- or out-parameters, in port order (inherited ones, `Super::name`, first). */
function parameters(diagram: DiagramMessage, kind: 'inParameter' | 'outParameter'): ParameterItem[] {
  return diagram.nodes
    .filter((n) => n.kind === kind)
    .map((n) => {
      const sep = n.id.lastIndexOf('::');
      return { name: n.label, typeName: n.typeName, from: n.inherited && sep > 0 ? n.id.slice(0, sep) : null };
    });
}
