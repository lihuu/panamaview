window.scriptLoaded = 'loaded';
window.fromJava = function (value) {
  var result = 'js:' + value;
  document.getElementById('java').textContent = result;
  return result;
};
window.toJava = function (value) {
  var result = window.javaBridge.echo(value);
  document.getElementById('bridge').textContent = result;
  return result;
};
