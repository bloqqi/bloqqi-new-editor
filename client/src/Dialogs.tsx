import { AddForm } from './AddForm';
import { ContextMenu } from './ContextMenu';
import { ExternalFunctionForm } from './ExternalFunctionForm';
import { PromptForm } from './PromptForm';
import type { Dialogs as DialogState } from './useDialogs';

/** Renders the open menu and form (see useDialogs). */
export function Dialogs({ dialogs: d, busy }: { dialogs: DialogState; busy: boolean }) {
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
