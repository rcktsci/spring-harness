import { app, Menu, type MenuItemConstructorOptions } from 'electron';

export function buildAppMenu(opts: {
  onOpenLogs: () => void;
  isDev: boolean;
}): Menu {
  const isMac = process.platform === 'darwin';

  const macAppMenu: MenuItemConstructorOptions[] = isMac
    ? [
      {
        label: app.name,
        submenu: [
          { role: 'about' },
          { type: 'separator' },
          { role: 'services' },
          { type: 'separator' },
          { role: 'hide' },
          { role: 'hideOthers' },
          { role: 'unhide' },
          { type: 'separator' },
          { role: 'quit' },
        ],
      },
    ]
    : [];

  const viewSubmenu: MenuItemConstructorOptions[] = [
    { role: 'reload' },
    { role: 'forceReload' },
    ...(opts.isDev ? [{ role: 'toggleDevTools' as const }] : []),
    { type: 'separator' },
    { role: 'resetZoom' },
    { role: 'zoomIn' },
    { role: 'zoomOut' },
    { type: 'separator' },
    { role: 'togglefullscreen' },
  ];

  const template: MenuItemConstructorOptions[] = [
    ...macAppMenu,
    {
      label: 'File',
      submenu: [isMac ? { role: 'close' } : { role: 'quit' }],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' },
      ],
    },
    { label: 'View', submenu: viewSubmenu },
    {
      label: 'Help',
      submenu: [
        {
          label: 'Open Logs',
          click: () => {
            opts.onOpenLogs();
          },
        },
        { role: 'about' },
      ],
    },
  ];

  return Menu.buildFromTemplate(template);
}
