
import './style.css';
import { CapgoWatch } from '@capgo/capacitor-watch';

const plugin = CapgoWatch;
const state = {};


const actions = [
{
              id: 'get-info',
              label: 'Get Watch Info',
              description: 'Calls getInfo() to discover watch connectivity status.',
              inputs: [],
              run: async (values) => {
                const info = await plugin.getInfo();
return info;
              },
            },
{
              id: 'get-plugin-version',
              label: 'Get Plugin Version',
              description: 'Gets the native plugin version.',
              inputs: [],
              run: async (values) => {
                const result = await plugin.getPluginVersion();
return result;
              },
            },
{
              id: 'send-message',
              label: 'Send Message',
              description: 'Sends an interactive message to the watch. Watch must be reachable.',
              inputs: [{ name: 'action', label: 'Action', type: 'text', value: 'hello' }, { name: 'value', label: 'Value', type: 'text', value: 'world' }],
              run: async (values) => {
                const result = await plugin.sendMessage({
  data: { action: values.action, value: values.value }
});
return result || 'Message sent';
              },
            },
{
              id: 'update-context',
              label: 'Update Application Context',
              description: 'Updates the shared application context. Only the latest context is kept.',
              inputs: [{ name: 'key', label: 'Key', type: 'text', value: 'status' }, { name: 'value', label: 'Value', type: 'text', value: 'active' }],
              run: async (values) => {
                const context = {};
                context[values.key] = values.value;
                const result = await plugin.updateApplicationContext({ context });
return result || 'Context updated';
              },
            },
{
              id: 'transfer-user-info',
              label: 'Transfer User Info',
              description: 'Queues user info for delivery to the watch, even when not reachable.',
              inputs: [{ name: 'key', label: 'Key', type: 'text', value: 'data' }, { name: 'value', label: 'Value', type: 'text', value: 'important' }],
              run: async (values) => {
                const userInfo = {};
                userInfo[values.key] = values.value;
                const result = await plugin.transferUserInfo({ userInfo });
return result || 'User info queued';
              },
            },
{
  id: 'setup-listeners',
  label: 'Setup Event Listeners',
  description: 'Sets up listeners for all watch events. Check console for events.',
  inputs: [],
  run: async (values) => {
    await plugin.addListener('messageReceived', (event) => {
      console.log('messageReceived:', event);
      state.lastMessage = event;
    });
    await plugin.addListener('messageReceivedWithReply', (event) => {
      console.log('messageReceivedWithReply:', event);
      state.lastMessageWithReply = event;
    });
    await plugin.addListener('applicationContextReceived', (event) => {
      console.log('applicationContextReceived:', event);
      state.lastContext = event;
    });
    await plugin.addListener('userInfoReceived', (event) => {
      console.log('userInfoReceived:', event);
      state.lastUserInfo = event;
    });
    await plugin.addListener('reachabilityChanged', (event) => {
      console.log('reachabilityChanged:', event);
      state.isReachable = event.isReachable;
    });
    await plugin.addListener('activationStateChanged', (event) => {
      console.log('activationStateChanged:', event);
      state.activationState = event.state;
    });
    return 'All listeners setup. Check console for events.';
  },
},
{
  id: 'remove-listeners',
  label: 'Remove All Listeners',
  description: 'Removes all event listeners.',
  inputs: [],
  run: async (values) => {
    await plugin.removeAllListeners();
    return 'All listeners removed.';
  },
}
];

const actionSelect = document.getElementById('action-select');
const formContainer = document.getElementById('action-form');
const descriptionBox = document.getElementById('action-description');
const runButton = document.getElementById('run-action');
const output = document.getElementById('plugin-output');

function buildForm(action) {
  formContainer.innerHTML = '';
  if (!action.inputs || !action.inputs.length) {
    const note = document.createElement('p');
    note.className = 'no-input-note';
    note.textContent = 'This action does not require any inputs.';
    formContainer.appendChild(note);
    return;
  }
  action.inputs.forEach((input) => {
    const fieldWrapper = document.createElement('div');
    fieldWrapper.className = input.type === 'checkbox' ? 'form-field inline' : 'form-field';

    const label = document.createElement('label');
    label.textContent = input.label;
    label.htmlFor = `field-${input.name}`;

    let field;
    switch (input.type) {
      case 'textarea': {
        field = document.createElement('textarea');
        field.rows = input.rows || 4;
        break;
      }
      case 'select': {
        field = document.createElement('select');
        (input.options || []).forEach((option) => {
          const opt = document.createElement('option');
          opt.value = option.value;
          opt.textContent = option.label;
          if (input.value !== undefined && option.value === input.value) {
            opt.selected = true;
          }
          field.appendChild(opt);
        });
        break;
      }
      case 'checkbox': {
        field = document.createElement('input');
        field.type = 'checkbox';
        field.checked = Boolean(input.value);
        break;
      }
      case 'number': {
        field = document.createElement('input');
        field.type = 'number';
        if (input.value !== undefined && input.value !== null) {
          field.value = String(input.value);
        }
        break;
      }
      default: {
        field = document.createElement('input');
        field.type = 'text';
        if (input.value !== undefined && input.value !== null) {
          field.value = String(input.value);
        }
      }
    }

    field.id = `field-${input.name}`;
    field.name = input.name;
    field.dataset.type = input.type || 'text';

    if (input.placeholder && input.type !== 'checkbox') {
      field.placeholder = input.placeholder;
    }

    if (input.type === 'checkbox') {
      fieldWrapper.appendChild(field);
      fieldWrapper.appendChild(label);
    } else {
      fieldWrapper.appendChild(label);
      fieldWrapper.appendChild(field);
    }

    formContainer.appendChild(fieldWrapper);
  });
}

function getFormValues(action) {
  const values = {};
  (action.inputs || []).forEach((input) => {
    const field = document.getElementById(`field-${input.name}`);
    if (!field) return;
    switch (input.type) {
      case 'number': {
        values[input.name] = field.value === '' ? null : Number(field.value);
        break;
      }
      case 'checkbox': {
        values[input.name] = field.checked;
        break;
      }
      default: {
        values[input.name] = field.value;
      }
    }
  });
  return values;
}

function setAction(action) {
  descriptionBox.textContent = action.description || '';
  buildForm(action);
  output.textContent = 'Ready to run the selected action.';
}

function populateActions() {
  actionSelect.innerHTML = '';
  actions.forEach((action) => {
    const option = document.createElement('option');
    option.value = action.id;
    option.textContent = action.label;
    actionSelect.appendChild(option);
  });
  setAction(actions[0]);
}

actionSelect.addEventListener('change', () => {
  const action = actions.find((item) => item.id === actionSelect.value);
  if (action) {
    setAction(action);
  }
});

runButton.addEventListener('click', async () => {
  const action = actions.find((item) => item.id === actionSelect.value);
  if (!action) return;
  const values = getFormValues(action);
  try {
    const result = await action.run(values);
    if (result === undefined) {
      output.textContent = 'Action completed.';
    } else if (typeof result === 'string') {
      output.textContent = result;
    } else {
      output.textContent = JSON.stringify(result, null, 2);
    }
  } catch (error) {
    output.textContent = `Error: ${error?.message ?? error}`;
  }
});

populateActions();
